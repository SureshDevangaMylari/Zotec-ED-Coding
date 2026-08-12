package wl.ai.rag.rag_spring;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import wl.ai.LLMService;
import wl.ai.rag.RAG_Coding.IcdAlphaIndexTraversalService;
import wl.ai.rag.RAG_Coding.IcdPracticalCodingRules;
import wl.ai.rag.RAG_Coding.QdrantRagStorage;
import wl.ai.ragICD.RagAlphaResult;
import wl.ai.ragICD.RagLookupResult;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * RAG for ICD lookup: embeddings stored in <strong>Qdrant</strong> (default) or legacy NDJSON files
 * ({@code RAG_STORAGE=file}).
 *
 * Insert phase:
 *  - Parse resources/icdalpha_2026.(json|txt) (JSON tree with title, code, see, children[])
 *  - Embed and upsert to Qdrant collections {@link QdrantRagStorage#COLLECTION_ICD} /
 *    {@link QdrantRagStorage#COLLECTION_GUIDELINES}, or write NDJSON under resources/RAG_coding/
 *  - Chunk resources/ICD-Guidlines.txt and embed same way
 *
 * Runtime:
 *  - Embed query via Spring AI {@link EmbeddingModel}; vector search Qdrant (or scan files); LLM applies rules + retrieved chunks.
 */
@Service
public class RagAlphaSpringService {

    private static final Logger log = LoggerFactory.getLogger(RagAlphaSpringService.class);
    /** Embedding API calls per batch (was 1 call per row — huge slowdown on large icdalpha). */
    private static final int EMBED_BATCH_SIZE = 64;
    /** Points per Qdrant HTTP upsert (avoid huge request bodies). */
    private static final int QDRANT_UPSERT_CHUNK = 128;
    /** Vector search oversample when applying keyword filter (then re-rank in Java). */
    private static final int QDRANT_SEARCH_PREFILTER_LIMIT = 512;

    private final ObjectMapper mapper = new ObjectMapper();
    /** Chat + JSON parsing — same as {@link wl.ai.rag.RAG_Coding.RagAlphaService}. Embeddings use {@link #embeddingModel}. */
    private final LLMService llm = new LLMService();
    private final EmbeddingModel embeddingModel;

    private final Path projectRoot;
    private final Path resourcesDir;
    private final Path ragFolder;
    private final Path icdAlphaEmbedFile;
    private final Path guidelineEmbedFile;
    private final IcdAlphaIndexTraversalService indexTraversal = new IcdAlphaIndexTraversalService();
    private final boolean useQdrant;
    private final QdrantRagStorage qdrant;

    public RagAlphaSpringService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.projectRoot = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        this.resourcesDir = projectRoot.resolve("resources");
        this.ragFolder = resourcesDir.resolve("RAG_coding");
        this.icdAlphaEmbedFile = ragFolder.resolve("icdalpha_2026.txt");
        this.guidelineEmbedFile = ragFolder.resolve("ICD-Guidlines.txt");
        this.useQdrant = QdrantRagStorage.useQdrant();
        this.qdrant = new QdrantRagStorage(QdrantRagStorage.resolveBaseUrl(), mapper);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Insert phase
    // ═══════════════════════════════════════════════════════════════════════════

    public void insert() throws IOException {
        if (!useQdrant) {
            Files.createDirectories(ragFolder);
        }
        Path alphaPath = resolveFirstExisting(
                resourcesDir.resolve("icdalpha_2026.txt"),
                resourcesDir.resolve("icdalpha_2026.json")
        );
        if (alphaPath == null) {
            throw new IOException("icdalpha_2026.(txt|json) not found under " + resourcesDir);
        }
        Path guidelinesPath = resourcesDir.resolve("ICD-Guidlines.txt");
        if (!Files.exists(guidelinesPath)) {
            throw new IOException("ICD-Guidlines.txt not found under " + resourcesDir);
        }

        if (useQdrant) {
            log.info("Insert: using Qdrant at {}", qdrant.getBaseUrl());
            qdrant.healthOrThrow();
            qdrant.deleteCollectionIfExists(QdrantRagStorage.COLLECTION_ICD);
            qdrant.deleteCollectionIfExists(QdrantRagStorage.COLLECTION_GUIDELINES);
        }

        upsertIcdAlpha(alphaPath, icdAlphaEmbedFile);
        upsertGuidelines(guidelinesPath, guidelineEmbedFile);
    }

    private void upsertIcdAlpha(Path alphaJson, Path outFile) throws IOException {
        if (useQdrant) {
            QdrantEmbedBatchWriter batch = new QdrantEmbedBatchWriter(
                    qdrant, QdrantRagStorage.COLLECTION_ICD, embeddingModel, EMBED_BATCH_SIZE, QDRANT_UPSERT_CHUNK);
            ingestIcdAlphaFromPath(alphaJson, batch);
            batch.flush();
            log.info("Finished icdalpha embeddings in Qdrant: {} points", batch.getTotalWritten());
            return;
        }
        try (BufferedWriter bw = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            FileEmbedBatchWriter batch = new FileEmbedBatchWriter(bw, mapper, embeddingModel, EMBED_BATCH_SIZE);
            ingestIcdAlphaFromPath(alphaJson, batch);
            batch.flush();
            log.info("Finished icdalpha embeddings: {} rows written", batch.getTotalWritten());
        }
    }

    private void ingestIcdAlphaFromPath(Path alphaJson, EmbeddingBatchSink batch) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(alphaJson, StandardCharsets.UTF_8)) {
            String first = br.readLine();
            if (first == null) throw new IOException("Empty file: " + alphaJson);
            if (first.trim().startsWith("{") || first.trim().startsWith("[")) {
                try (FileInputStream fis = new FileInputStream(alphaJson.toFile())) {
                    JsonFactory jf = new JsonFactory();
                    JsonParser jp = jf.createParser(fis);
                    JsonToken t = jp.nextToken();
                    if (t == JsonToken.START_ARRAY) {
                        while (jp.nextToken() != JsonToken.END_ARRAY) {
                            Map<?, ?> node = mapper.readValue(jp, Map.class);
                            ingestAlphaNode(node, new ArrayList<>(), batch);
                        }
                    } else if (t == JsonToken.START_OBJECT) {
                        Map<?, ?> node = mapper.readValue(jp, Map.class);
                        ingestAlphaNode(node, new ArrayList<>(), batch);
                    }
                }
            } else {
                String line = first;
                while (line != null) {
                    if (!line.isBlank()) {
                        Map<?, ?> node = mapper.readValue(line, Map.class);
                        ingestAlphaNode(node, new ArrayList<>(), batch);
                    }
                    line = br.readLine();
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void ingestAlphaNode(Object raw, List<String> path, EmbeddingBatchSink batch) throws IOException {
        if (!(raw instanceof Map)) return;
        Map<String, Object> node = (Map<String, Object>) raw;
        String title = str(node.get("title"));
        String code = str(node.get("code"));
        String see = str(node.get("see"));
        List<String> newPath = new ArrayList<>(path);
        if (title != null && !title.isBlank()) newPath.add(title);

        if ((code != null && !code.isBlank()) || (see != null && !see.isBlank())) {
            String searchable = String.join(" > ", newPath);
            if (see != null && !see.isBlank()) searchable = searchable + " see " + see;

            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "icd");
            if (code != null) payload.put("code", code);
            if (title != null) payload.put("title", title);
            payload.put("path", newPath);
            if (see != null) payload.put("see", see);
            payload.put("searchable", searchable);

            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", UUID.nameUUIDFromBytes(searchable.getBytes(StandardCharsets.UTF_8)).toString());
            e.put("payload", payload);
            batch.add(searchable.toLowerCase(Locale.ROOT), e);
        }

        Object children = node.get("children");
        if (children instanceof List<?>) {
            for (Object ch : (List<?>) children) {
                ingestAlphaNode(ch, newPath, batch);
            }
        }
    }

    private void upsertGuidelines(Path guidelinesTxt, Path outFile) throws IOException {
        List<String> lines = Files.readAllLines(guidelinesTxt, StandardCharsets.UTF_8);
        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String line : lines) {
            if (line.trim().isEmpty()) {
                if (buf.length() > 0) {
                    chunks.add(buf.toString().trim());
                    buf.setLength(0);
                }
            } else {
                if (buf.length() > 0) buf.append('\n');
                buf.append(line);
                if (buf.length() > 1200) {
                    chunks.add(buf.toString().trim());
                    buf.setLength(0);
                }
            }
        }
        if (buf.length() > 0) chunks.add(buf.toString().trim());

        if (useQdrant) {
            QdrantEmbedBatchWriter batch = new QdrantEmbedBatchWriter(
                    qdrant, QdrantRagStorage.COLLECTION_GUIDELINES, embeddingModel, EMBED_BATCH_SIZE, QDRANT_UPSERT_CHUNK);
            int idx = 0;
            for (String chunk : chunks) {
                if (chunk.isBlank()) continue;
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "guideline");
                payload.put("text", chunk);
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("id", UUID.nameUUIDFromBytes(("guideline:" + (idx++)).getBytes(StandardCharsets.UTF_8)).toString());
                rec.put("payload", payload);
                batch.add(chunk.toLowerCase(Locale.ROOT), rec);
            }
            batch.flush();
            log.info("Finished guideline embeddings in Qdrant: {} points", batch.getTotalWritten());
            return;
        }

        try (BufferedWriter bw = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            FileEmbedBatchWriter batch = new FileEmbedBatchWriter(bw, mapper, embeddingModel, EMBED_BATCH_SIZE);
            int idx = 0;
            for (String chunk : chunks) {
                if (chunk.isBlank()) continue;
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "guideline");
                payload.put("text", chunk);
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("id", UUID.nameUUIDFromBytes(("guideline:" + (idx++)).getBytes(StandardCharsets.UTF_8)).toString());
                rec.put("payload", payload);
                batch.add(chunk.toLowerCase(Locale.ROOT), rec);
            }
            batch.flush();
            log.info("Finished guideline embeddings: {} rows written", batch.getTotalWritten());
        }
    }

    private interface EmbeddingBatchSink {
        void add(String embedText, Map<String, Object> recordWithoutVector) throws IOException;

        void flush() throws IOException;

        int getTotalWritten();
    }

    /**
     * Buffers rows and embeds via Spring AI {@link EmbeddingModel} every {@code batchSize} items
     * instead of one HTTP call per row (major speedup for large icdalpha_2026.json).
     */
    private static final class FileEmbedBatchWriter implements EmbeddingBatchSink {
        private final BufferedWriter bw;
        private final ObjectMapper mapper;
        private final EmbeddingModel embeddingModel;
        private final int batchSize;
        private final List<String> texts = new ArrayList<>();
        private final List<Map<String, Object>> records = new ArrayList<>();
        private int totalWritten;

        FileEmbedBatchWriter(BufferedWriter bw, ObjectMapper mapper, EmbeddingModel embeddingModel, int batchSize) {
            this.bw = bw;
            this.mapper = mapper;
            this.embeddingModel = embeddingModel;
            this.batchSize = Math.max(1, batchSize);
        }

        @Override
        public void add(String embedText, Map<String, Object> recordWithoutVector) throws IOException {
            texts.add(embedText);
            records.add(recordWithoutVector);
            if (texts.size() >= batchSize) {
                flush();
            }
        }

        @Override
        public void flush() throws IOException {
            if (texts.isEmpty()) return;
            List<List<Double>> vecs = SpringAiEmbeddingSupport.embedBatchToDoubles(embeddingModel, texts);
            for (int i = 0; i < texts.size(); i++) {
                Map<String, Object> rec = records.get(i);
                List<Double> v = i < vecs.size() && vecs.get(i) != null && !vecs.get(i).isEmpty()
                        ? vecs.get(i)
                        : SpringAiEmbeddingSupport.embedToDoubles(embeddingModel, texts.get(i));
                rec.put("vector", v);
                bw.write(mapper.writeValueAsString(rec));
                bw.newLine();
                totalWritten++;
                if (totalWritten % 2000 == 0) {
                    log.info("Embedding progress: {} rows written", totalWritten);
                }
            }
            texts.clear();
            records.clear();
        }

        @Override
        public int getTotalWritten() {
            return totalWritten;
        }
    }

    /**
     * Embeds batches and upserts to Qdrant; creates the collection on first flush using embedding dimension.
     */
    private static final class QdrantEmbedBatchWriter implements EmbeddingBatchSink {
        private final QdrantRagStorage qdrant;
        private final String collection;
        private final EmbeddingModel embeddingModel;
        private final int batchSize;
        private final int upsertChunkSize;
        private final List<String> texts = new ArrayList<>();
        private final List<Map<String, Object>> records = new ArrayList<>();
        private boolean collectionCreated;
        private int totalWritten;

        QdrantEmbedBatchWriter(QdrantRagStorage qdrant, String collection, EmbeddingModel embeddingModel,
                               int batchSize, int upsertChunkSize) {
            this.qdrant = qdrant;
            this.collection = collection;
            this.embeddingModel = embeddingModel;
            this.batchSize = Math.max(1, batchSize);
            this.upsertChunkSize = Math.max(32, upsertChunkSize);
        }

        @Override
        public void add(String embedText, Map<String, Object> recordWithoutVector) throws IOException {
            texts.add(embedText);
            records.add(recordWithoutVector);
            if (texts.size() >= batchSize) {
                flush();
            }
        }

        @Override
        public void flush() throws IOException {
            if (texts.isEmpty()) return;
            List<List<Double>> vecs = SpringAiEmbeddingSupport.embedBatchToDoubles(embeddingModel, texts);
            List<Map<String, Object>> withVectors = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                Map<String, Object> rec = records.get(i);
                List<Double> v = i < vecs.size() && vecs.get(i) != null && !vecs.get(i).isEmpty()
                        ? vecs.get(i)
                        : SpringAiEmbeddingSupport.embedToDoubles(embeddingModel, texts.get(i));
                rec.put("vector", v);
                withVectors.add(rec);
                if (!collectionCreated) {
                    qdrant.createCollectionCosine(collection, v.size());
                    collectionCreated = true;
                }
            }
            qdrant.upsertPoints(collection, withVectors, upsertChunkSize);
            totalWritten += texts.size();
            if (totalWritten % 2000 == 0) {
                log.info("Qdrant embedding progress: {} points upserted to {}", totalWritten, collection);
            }
            texts.clear();
            records.clear();
        }

        @Override
        public int getTotalWritten() {
            return totalWritten;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Runtime phase
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Lookup ICD codes from a sentence. A sentence may contain multiple diagnoses.
     * Flow: 1) Guidelines (ICD-Guidlines.txt) lead how to code; 2) icdalpha_2026 index (embedded) supplies See/children/codes;
     * 3) LLM picks codes consistent with both; see {@link IcdPracticalCodingRules#ICDALPHA_JSON_LEAD}.
     */
    public RagAlphaResult lookupMultiple(String sentence, int topK, int topGuidelines) throws IOException {
        String q = sentence != null ? sentence.trim() : "";
        if (q.isEmpty()) return RagAlphaResult.error("Empty query");
        ensureEmbedded();

        List<Double> qVec = SpringAiEmbeddingSupport.embedToDoubles(embeddingModel, q.toLowerCase(Locale.ROOT));
        List<String> tokens = tokenizeForTitleSearch(q);

        // Step 1: Retrieve guidelines FIRST - these define how to code
        List<Map<String, Object>> glHits = useQdrant
                ? topFromQdrant(QdrantRagStorage.COLLECTION_GUIDELINES, qVec, topGuidelines, tokens, false)
                : topFromFile(guidelineEmbedFile, qVec, topGuidelines, tokens, false);

        // Step 2: Retrieve ICD candidates from icdalpha_2026
        List<Map<String, Object>> icdHits = useQdrant
                ? topFromQdrant(QdrantRagStorage.COLLECTION_ICD, qVec, topK, tokens, true)
                : topFromFile(icdAlphaEmbedFile, qVec, topK, tokens, true);

        if (icdHits.isEmpty()) return RagAlphaResult.error("No ICD candidates found");

        String systemPrompt = "You are a medical coder following ICD-10-CM. " +
                "Guidelines LEAD how to interpret the diagnosis; icdalpha_2026 (candidates below) is the alphabetic index — use See/children per ICDALPHA_JSON_LEAD. " +
                "Order: practical rules → guideline excerpts → index navigation (See chain) → pick code → Tabular consistency. " +
                "When rules conflict, official guideline excerpts win. " +
                "Identify ALL distinct diagnoses in the sentence. " +
                "Return ONLY valid JSON: {\"icd_codes\":[{\"code\":\"...\",\"description\":\"...\",\"confidence\":\"high|medium|low\"},...]}";
        String userPrompt = buildUserPromptMultiple(q, icdHits, glHits);
        try {
            String raw = llm.call(systemPrompt, userPrompt, 2048, 0.1);
            return parseMultiIcdResponse(raw, q);
        } catch (IOException e) {
            return RagAlphaResult.error(e.getMessage());
        }
    }

    /** Single-code lookup (backward compatible). */
    public RagLookupResult lookup(String sentence, int topK, int topGuidelines) throws IOException {
        RagAlphaResult multi = lookupMultiple(sentence, topK, topGuidelines);
        if (!multi.success()) return RagLookupResult.error(multi.errorMessage());
        if (multi.icdCodes().isEmpty()) return RagLookupResult.error("No ICD codes found");
        RagAlphaResult.IcdEntry first = multi.icdCodes().get(0);
        return RagLookupResult.icd(first.code(), first.description(), first.confidence());
    }

    private RagAlphaResult parseMultiIcdResponse(String raw, String query) {
        Map<String, Object> parsed = llm.parseToMap(raw);
        Object rawObj = parsed.get("raw");
        if (rawObj != null) return RagAlphaResult.error("Parse failed: " + rawObj.toString().substring(0, Math.min(100, rawObj.toString().length())));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> codes = (List<Map<String, Object>>) parsed.get("icd_codes");
        if (codes == null || codes.isEmpty()) return RagAlphaResult.error("No icd_codes in response");

        List<RagAlphaResult.IcdEntry> entries = new ArrayList<>();
        for (Map<String, Object> m : codes) {
            String code = str(m.get("code"));
            if (code == null || code.isBlank()) continue;
            String desc = str(m.get("description"));
            String conf = str(m.get("confidence"));
            entries.add(new RagAlphaResult.IcdEntry(code, desc != null ? desc : code, conf));
        }
        return entries.isEmpty() ? RagAlphaResult.error("No valid codes") : RagAlphaResult.ok(entries);
    }

    private String buildUserPromptMultiple(String q, List<Map<String, Object>> icd, List<Map<String, Object>> gl) {
        StringBuilder indexWalk = new StringBuilder();
        Optional<IcdAlphaIndexTraversalService.TraversalResult> walk = indexTraversal.tryResolveUseOfChain(q);
        if (walk.isPresent()) {
            IcdAlphaIndexTraversalService.TraversalResult tr = walk.get();
            indexWalk.append("## Programmatic index walk (icdalpha_2026.json — Use (of) → See → subterms → default)\n");
            indexWalk.append("Resolved path: ").append(String.join(" → ", tr.pathTitles())).append("\n");
            indexWalk.append("Index lead code at resolved line (when no subterm matches the wording): ").append(tr.code()).append("\n");
            indexWalk.append(tr.explanation()).append("\n\n");
        }

        StringBuilder guidelines = new StringBuilder();
        for (Map<String, Object> rec : gl) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) rec.get("payload");
            String text = str(p != null ? p.get("text") : null);
            if (text != null && !text.isBlank()) guidelines.append(text).append("\n---\n");
        }

        StringBuilder entries = new StringBuilder();
        for (Map<String, Object> rec : icd) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) rec.get("payload");
            String code = str(p != null ? p.get("code") : null);
            String title = str(p != null ? p.get("title") : null);
            String searchable = str(p != null ? p.get("searchable") : null);
            String seeRef = str(p != null ? p.get("see") : null);
            if ((code == null || code.isBlank()) && (seeRef == null || seeRef.isBlank())) continue;
            entries.append("- ");
            if (code != null && !code.isBlank()) entries.append(code).append(": ");
            entries.append(title != null ? title : searchable != null ? searchable : "(entry)");
            if (seeRef != null && !seeRef.isBlank()) entries.append(" | See: ").append(seeRef);
            entries.append("\n");
        }

        return "## Sentence (extract ALL diagnoses):\n" + q + "\n\n" + indexWalk +
                "## PRACTICAL RULES (always follow when selecting / sequencing):\n" + IcdPracticalCodingRules.TEXT + "\n\n" +
                "## INDEX TRAVERSAL (See chain → subterms → default code — match CodeQuest logic):\n" + IcdPracticalCodingRules.INDEX_TRAVERSAL + "\n\n" +
                "## HOW GUIDELINES LEAD THE CODE IN icdalpha_2026.json:\n" + IcdPracticalCodingRules.ICDALPHA_JSON_LEAD + "\n\n" +
                "## ICD-10-CM Guidelines (from ICD-Guidlines.txt — apply first; they tell you which index path and coding rules):\n" + guidelines + "\n\n" +
                "## ICD candidates from embedded icdalpha index (title, code, See — navigate per sections above):\n" + entries + "\n\n" +
                "Apply: most specific code, combination codes when applicable, no duplicate conditions, code first / additional; ignore Excludes2 as a blocker in this workflow. " +
                "If the query contains the words \"use\" or \"with\", follow the Use (of) chain (See → stimulant NEC, etc.); do not prefer amphetamine-type substance use (mild/moderate/severe) over that path. " +
                "If the query contains neither \"use\" nor \"with\" as words, you may use amphetamine-type substance use with severity when the index matches. " +
                "Otherwise follow See to stimulant (or target) NEC, then subterms if documented; else default code at that resolved index line; confirm in Tabular.\n" +
                "Return JSON: {\"icd_codes\":[{\"code\":\"...\",\"description\":\"...\",\"confidence\":\"high|medium|low\"},...]}";
    }

    private List<Map<String, Object>> topFromQdrant(String collection,
                                                    List<Double> qVec,
                                                    int topK,
                                                    List<String> tokens,
                                                    boolean keywordFilter) throws IOException {
        int limit = keywordFilter && tokens != null && !tokens.isEmpty()
                ? QDRANT_SEARCH_PREFILTER_LIMIT
                : Math.max(topK, 32);
        List<Scored> scored = scoreFromQdrant(collection, qVec, tokens, keywordFilter, limit);
        if (scored.isEmpty() && keywordFilter) {
            scored = scoreFromQdrant(collection, qVec, tokens, false, Math.max(topK, 32));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        List<Map<String, Object>> out = new ArrayList<>();
        int lim = Math.min(topK, scored.size());
        for (int i = 0; i < lim; i++) out.add(scored.get(i).record);
        return out;
    }

    private List<Scored> scoreFromQdrant(String collection,
                                         List<Double> qVec,
                                         List<String> tokens,
                                         boolean keywordFilter,
                                         int limit) throws IOException {
        List<QdrantRagStorage.SearchHit> hits = qdrant.searchWithScores(collection, qVec, limit);
        List<Scored> scored = new ArrayList<>();
        for (QdrantRagStorage.SearchHit hit : hits) {
            Map<String, Object> rec = hit.record();
            if (keywordFilter && tokens != null && !tokens.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) rec.get("payload");
                String searchable = p != null ? str(p.get("searchable")) : null;
                String title = p != null ? str(p.get("title")) : null;
                String text = p != null ? str(p.get("text")) : null;
                String hay = (nullToEmpty(searchable) + " " + nullToEmpty(title) + " " + nullToEmpty(text))
                        .toLowerCase(Locale.ROOT);
                boolean any = false;
                for (String t : tokens) {
                    if (hay.contains(t)) {
                        any = true;
                        break;
                    }
                }
                if (!any) continue;
            }
            scored.add(new Scored(rec, hit.score()));
        }
        return scored;
    }

    private List<Map<String, Object>> topFromFile(Path file,
                                                  List<Double> qVec,
                                                  int topK,
                                                  List<String> tokens,
                                                  boolean keywordFilter) throws IOException {
        List<Scored> scored = scoreFromFile(file, qVec, tokens, keywordFilter);
        if (scored.isEmpty() && keywordFilter) {
            scored = scoreFromFile(file, qVec, tokens, false);
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        List<Map<String, Object>> out = new ArrayList<>();
        int lim = Math.min(topK, scored.size());
        for (int i = 0; i < lim; i++) out.add(scored.get(i).record);
        return out;
    }

    private List<Scored> scoreFromFile(Path file,
                                       List<Double> qVec,
                                       List<String> tokens,
                                       boolean keywordFilter) throws IOException {
        List<Scored> scored = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                Map<String, Object> rec = mapper.readValue(line, Map.class);

                if (keywordFilter && tokens != null && !tokens.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> p = (Map<String, Object>) rec.get("payload");
                    String searchable = p != null ? str(p.get("searchable")) : null;
                    String title = p != null ? str(p.get("title")) : null;
                    String hay = (nullToEmpty(searchable) + " " + nullToEmpty(title)).toLowerCase(Locale.ROOT);
                    boolean any = false;
                    for (String t : tokens) {
                        if (hay.contains(t)) {
                            any = true;
                            break;
                        }
                    }
                    if (!any) continue;
                }

                @SuppressWarnings("unchecked")
                List<Number> vecN = (List<Number>) rec.get("vector");
                if (vecN == null || vecN.isEmpty()) continue;
                List<Double> vec = new ArrayList<>(vecN.size());
                for (Number n : vecN) vec.add(n.doubleValue());
                double score = cosine(qVec, vec);
                scored.add(new Scored(rec, score));
            }
        }
        return scored;
    }


    private void ensureEmbedded() throws IOException {
        if (useQdrant) {
            qdrant.healthOrThrow();
            if (!qdrant.collectionExists(QdrantRagStorage.COLLECTION_ICD)
                    || qdrant.collectionPointsCount(QdrantRagStorage.COLLECTION_ICD) == 0) {
                throw new IOException("Qdrant collection '" + QdrantRagStorage.COLLECTION_ICD
                        + "' is missing or empty. Run insert with Qdrant at " + qdrant.getBaseUrl());
            }
            if (!qdrant.collectionExists(QdrantRagStorage.COLLECTION_GUIDELINES)
                    || qdrant.collectionPointsCount(QdrantRagStorage.COLLECTION_GUIDELINES) == 0) {
                throw new IOException("Qdrant collection '" + QdrantRagStorage.COLLECTION_GUIDELINES
                        + "' is missing or empty. Run insert with Qdrant at " + qdrant.getBaseUrl());
            }
            return;
        }
        if (!Files.exists(icdAlphaEmbedFile) || !Files.exists(guidelineEmbedFile)) {
            throw new IOException("Embedding files missing. Run insert first.\n" +
                    "Expected: " + icdAlphaEmbedFile + " and " + guidelineEmbedFile);
        }
    }

    private static Path resolveFirstExisting(Path... candidates) {
        for (Path p : candidates) if (Files.exists(p)) return p;
        return null;
    }

    private static List<String> tokenizeForTitleSearch(String q) {
        if (q == null) return List.of();
        String s = q.toLowerCase(Locale.ROOT);
        String[] parts = s.split("[^a-z0-9]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.length() < 3) continue;
            out.add(t);
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static double cosine(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return -1.0;
        int n = Math.min(a.size(), b.size());
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na == 0 || nb == 0) return -1.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static final class Scored {
        final Map<String, Object> record;
        final double score;
        Scored(Map<String, Object> r, double s) { this.record = r; this.score = s; }
    }
}

