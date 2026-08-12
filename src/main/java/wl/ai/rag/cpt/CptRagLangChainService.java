package wl.ai.rag.cpt;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wl.ai.LLMService;
import wl.ai.rag.RAG_Coding.QdrantRagStorage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CPT RAG aligned with {@link wl.ai.rag.RAG_Coding.RagAlphaService}: {@link LLMService} for embeddings and chat,
 * {@link QdrantRagStorage} (HTTP REST to Qdrant) for vectors — same stack as ICD RAG.
 */
public class CptRagLangChainService {

    private static final Logger log = LoggerFactory.getLogger(CptRagLangChainService.class);

    public static final String COLLECTION = "cpt_rag";

    private static final int EMBED_BATCH_SIZE = 64;
    private static final int QDRANT_UPSERT_CHUNK = 128;
    private static final int TOP_K = 18;
    private static final int MAX_CONTEXT_CHARS = 12000;

    private final Path projectRoot;
    private final Path resourcesDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final LLMService llm = new LLMService();
    private final QdrantRagStorage qdrant;

    public CptRagLangChainService() {
        this.projectRoot = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        this.resourcesDir = projectRoot.resolve("resources");
        this.qdrant = new QdrantRagStorage(QdrantRagStorage.resolveBaseUrl(), mapper);
    }

    public void insert() throws IOException {
        Path codes = resolveCptCodesPath();
        Path guidelines = resolveGuidelinesPath();
        if (!Files.exists(codes)) {
            throw new IOException("CPT codes file not found: " + codes);
        }
        if (!Files.exists(guidelines)) {
            throw new IOException("CPT guidelines file not found: " + guidelines);
        }

        log.info("CPT insert: Qdrant at {}", qdrant.getBaseUrl());
        qdrant.healthOrThrow();
        qdrant.deleteCollectionIfExists(COLLECTION);

        upsertGuidelines(guidelines);
        upsertCptCatalog(codes);
        upsertJsonFolder(projectRoot.resolve("resources/jsonfolder"));

        log.info("CPT insert complete. Collection {}", COLLECTION);
    }

    private void upsertGuidelines(Path guidelinesTxt) throws IOException {
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

        QdrantEmbedBatchWriter batch = new QdrantEmbedBatchWriter(
                qdrant, COLLECTION, mapper, llm, EMBED_BATCH_SIZE, QDRANT_UPSERT_CHUNK);
        int idx = 0;
        for (String chunk : chunks) {
            if (chunk.isBlank()) continue;
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "guideline");
            payload.put("text", chunk);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("id", UUID.nameUUIDFromBytes(("cpt-guideline:" + (idx++)).getBytes(StandardCharsets.UTF_8)).toString());
            rec.put("payload", payload);
            batch.add(chunk.toLowerCase(Locale.ROOT), rec);
        }
        batch.flush();
        log.info("Guideline chunks upserted: {}", batch.getTotalWritten());
    }

    @SuppressWarnings("unchecked")
    private void upsertCptCatalog(Path codesJson) throws IOException {
        QdrantEmbedBatchWriter batch = new QdrantEmbedBatchWriter(
                qdrant, COLLECTION, mapper, llm, EMBED_BATCH_SIZE, QDRANT_UPSERT_CHUNK);
        JsonFactory jf = new JsonFactory();
        try (var in = Files.newInputStream(codesJson);
             var jp = jf.createParser(in)) {
            if (jp.nextToken() != JsonToken.START_ARRAY) {
                throw new IOException("Expected JSON array in " + codesJson);
            }
            while (jp.nextToken() == JsonToken.START_OBJECT) {
                Map<String, Object> row = mapper.readValue(jp, Map.class);
                TextRow tr = cptRowToText(row);
                if (tr == null) continue;
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "cpt");
                if (tr.code != null && !tr.code.isBlank()) payload.put("code", tr.code);
                payload.put("searchable", tr.searchable);
                Map<String, Object> rec = new LinkedHashMap<>();
                rec.put("id", UUID.nameUUIDFromBytes(tr.searchable.getBytes(StandardCharsets.UTF_8)).toString());
                rec.put("payload", payload);
                batch.add(tr.embedText.toLowerCase(Locale.ROOT), rec);
            }
        }
        batch.flush();
        log.info("CPT catalog rows upserted: {}", batch.getTotalWritten());
    }

    private static final class TextRow {
        final String code;
        final String searchable;
        final String embedText;

        TextRow(String code, String searchable, String embedText) {
            this.code = code;
            this.searchable = searchable;
            this.embedText = embedText;
        }
    }

    private TextRow cptRowToText(Map<String, Object> row) {
        Object codeObj = row.get("code");
        String code = codeObj != null ? codeObj.toString().trim() : "";
        String desc = str(row.get("desc"));
        String shortC = str(row.get("consumer_friendly_short"));
        String fullC = str(row.get("consumer_friendly_full"));
        if (code.isEmpty() && desc == null && shortC == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (!code.isEmpty()) {
            sb.append("CPT ").append(code).append(". ");
        }
        if (shortC != null) sb.append(shortC).append(". ");
        if (fullC != null) sb.append(fullC).append(". ");
        if (desc != null) sb.append(desc);
        String text = sb.toString().trim();
        if (text.length() > 8000) {
            text = text.substring(0, 8000);
        }
        return new TextRow(code, text, text);
    }

    private void upsertJsonFolder(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return;
        }
        QdrantEmbedBatchWriter batch = new QdrantEmbedBatchWriter(
                qdrant, COLLECTION, mapper, llm, EMBED_BATCH_SIZE, QDRANT_UPSERT_CHUNK);
        try (var stream = Files.list(folder)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".json") && !name.endsWith(".txt")) {
                    continue;
                }
                String content = Files.readString(p, StandardCharsets.UTF_8);
                List<String> chunks = chunkBySize(content, 1200);
                int i = 0;
                for (String chunk : chunks) {
                    if (chunk.isBlank()) continue;
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("type", "file");
                    payload.put("filename", p.getFileName().toString());
                    payload.put("text", chunk);
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("id", UUID.nameUUIDFromBytes((p + ":" + (i++)).getBytes(StandardCharsets.UTF_8)).toString());
                    rec.put("payload", payload);
                    batch.add(chunk.toLowerCase(Locale.ROOT), rec);
                }
            }
        }
        batch.flush();
        log.info("jsonfolder upserted points (this batch writer total): {}", batch.getTotalWritten());
    }

    private static List<String> chunkBySize(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        String t = text.replace("\r\n", "\n");
        int i = 0;
        while (i < t.length()) {
            int end = Math.min(i + maxChars, t.length());
            out.add(t.substring(i, end).trim());
            i = end;
        }
        return out.stream().filter(s -> !s.isBlank()).toList();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Same pattern as {@link wl.ai.rag.RAG_Coding.RagAlphaService}'s QdrantEmbedBatchWriter.
     */
    private interface EmbeddingBatchSink {
        void add(String embedText, Map<String, Object> recordWithoutVector) throws IOException;

        void flush() throws IOException;

        int getTotalWritten();
    }

    private static final class QdrantEmbedBatchWriter implements EmbeddingBatchSink {
        private final QdrantRagStorage qdrant;
        private final String collection;
        private final LLMService llm;
        private final int batchSize;
        private final int upsertChunkSize;
        private final List<String> texts = new ArrayList<>();
        private final List<Map<String, Object>> records = new ArrayList<>();
        private boolean collectionCreated;
        private int totalWritten;

        QdrantEmbedBatchWriter(QdrantRagStorage qdrant, String collection, ObjectMapper mapper, LLMService llm,
                               int batchSize, int upsertChunkSize) {
            this.qdrant = qdrant;
            this.collection = collection;
            this.llm = llm;
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
            List<List<Double>> vecs = llm.getEmbeddingsBatch(texts);
            List<Map<String, Object>> withVectors = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                Map<String, Object> rec = records.get(i);
                List<Double> v = i < vecs.size() && vecs.get(i) != null && !vecs.get(i).isEmpty()
                        ? vecs.get(i)
                        : llm.getEmbedding(texts.get(i));
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
                log.info("Qdrant CPT embedding progress: {} points upserted to {}", totalWritten, collection);
            }
            texts.clear();
            records.clear();
        }

        @Override
        public int getTotalWritten() {
            return totalWritten;
        }
    }

    public CptRagResult lookup(String userQuery, Path outputJson) throws IOException {
        String q = userQuery != null ? userQuery.trim() : "";
        if (q.isEmpty()) {
            return CptRagResult.error("Empty query");
        }
        ensureEmbedded();

        Path out = outputJson != null ? outputJson : projectRoot.resolve("resources/jsonfolder/output.json");
        String clinical = "";
        if (Files.exists(out)) {
            clinical = compactClinicalJson(Files.readString(out, StandardCharsets.UTF_8));
        } else {
            log.warn("output.json not found at {}", out);
        }

        String combined = (q + "\n\nClinical record (excerpt):\n" + clinical).trim();
        if (combined.length() > MAX_CONTEXT_CHARS) {
            combined = combined.substring(0, MAX_CONTEXT_CHARS);
        }

        List<Double> qVec = llm.getEmbedding(combined.toLowerCase(Locale.ROOT));
        if (qVec == null || qVec.isEmpty()) {
            return CptRagResult.error("Empty query embedding");
        }

        List<QdrantRagStorage.SearchHit> hits = qdrant.searchWithScores(COLLECTION, qVec, TOP_K);
        StringBuilder context = new StringBuilder();
        for (QdrantRagStorage.SearchHit hit : hits) {
            Map<String, Object> rec = hit.record();
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) rec.get("payload");
            if (p == null) continue;
            String t = str(p.get("text"));
            if (t == null) t = str(p.get("searchable"));
            if (t != null) context.append(t).append("\n---\n");
        }

        Path glPath = resolveGuidelinesPath();
        String guidelines = "";
        if (Files.exists(glPath)) {
            String g = Files.readString(glPath, StandardCharsets.UTF_8);
            guidelines = g.length() > 4000 ? g.substring(0, 4000) + "…" : g;
        }

        String systemPrompt = "You are a medical coding assistant. Select CPT codes consistent with AMA CPT rules and the retrieved context. "
                + "Return ONLY valid JSON: {\"cpt_codes\":[{\"code\":\"...\",\"description\":\"...\",\"confidence\":\"high|medium|low\"},...]}. "
                + "Include every distinct code that applies; use multiple entries when multiple procedures or services apply. "
                + "Keep descriptions short; you MUST close all brackets and quotes — complete, parseable JSON only.";

        String userPrompt = """
                ## CPT guidelines (reference)
                %s

                ## Retrieved chunks (from embedded index)
                %s

                ## Question / clinical context
                %s
                """.formatted(guidelines, context, combined);

        try {
            String raw = llm.call(systemPrompt, userPrompt, 6144, 0.1);
            return parseCptJson(raw);
        } catch (IOException e) {
            return CptRagResult.error(e.getMessage());
        }
    }

    private void ensureEmbedded() throws IOException {
        qdrant.healthOrThrow();
        if (!qdrant.collectionExists(COLLECTION) || qdrant.collectionPointsCount(COLLECTION) == 0) {
            throw new IOException("Qdrant collection '" + COLLECTION + "' is missing or empty. Run insert first. Qdrant at "
                    + qdrant.getBaseUrl());
        }
    }

    private CptRagResult parseCptJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return CptRagResult.error("Empty LLM response");
        }
        Exception last = null;
        for (String candidate : cptJsonCandidates(raw)) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                JsonNode root = mapper.readTree(candidate);
                var arr = root.get("cpt_codes");
                if (arr == null || !arr.isArray()) {
                    continue;
                }
                List<CptRagResult.CptEntry> entries = new ArrayList<>();
                for (JsonNode n : arr) {
                    String code = n.path("code").asText(null);
                    if (code == null || code.isBlank()) {
                        continue;
                    }
                    String desc = n.path("description").asText(code);
                    String conf = n.path("confidence").asText("medium");
                    entries.add(new CptRagResult.CptEntry(code.trim(), desc, conf));
                }
                if (!entries.isEmpty()) {
                    return CptRagResult.ok(entries);
                }
            } catch (Exception e) {
                last = e;
            }
        }
        CptRagResult lenient = lenientCptCodesFromText(raw);
        if (lenient.success() && !lenient.cptCodes().isEmpty()) {
            log.warn("CPT response used lenient code extraction (model JSON was incomplete or malformed)");
            return lenient;
        }
        String detail = last != null ? last.getMessage() : "no parseable cpt_codes";
        return CptRagResult.error("Parse failed: " + detail);
    }

    /** Try several slices: balanced object from first '{', then {@link LLMService#cleanJson(String)} (can mis-cut on truncation). */
    private List<String> cptJsonCandidates(String raw) {
        List<String> out = new ArrayList<>();
        String fenced = stripMarkdownCodeFences(raw);
        String balanced = extractBalancedJsonObject(fenced);
        out.add(balanced);
        if (!balanced.equals(llm.cleanJson(raw))) {
            out.add(llm.cleanJson(raw));
        }
        out.add(fenced.trim());
        return out;
    }

    private static String stripMarkdownCodeFences(String t) {
        if (t == null) {
            return "";
        }
        t = t.trim();
        t = t.replaceAll("(?s)```(?:json)?\\n?", "");
        return t.replace("```", "").trim();
    }

    /**
     * First top-level JSON object using brace/bracket depth (respects strings). Avoids
     * {@link LLMService#cleanJson(String)} taking an inner {@code '}'} as the end when output is truncated.
     */
    private static String extractBalancedJsonObject(String s) {
        int start = -1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '{') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return s.trim();
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return s.substring(start);
    }

    private static final Pattern CPT_CODE_FIELD = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /** Last resort: pull quoted {@code code} values from raw text (handles truncated JSON arrays). */
    private static CptRagResult lenientCptCodesFromText(String raw) {
        Matcher m = CPT_CODE_FIELD.matcher(raw);
        List<CptRagResult.CptEntry> entries = new ArrayList<>();
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
        while (m.find()) {
            String code = m.group(1).trim();
            if (code.isEmpty() || seen.containsKey(code)) {
                continue;
            }
            seen.put(code, Boolean.TRUE);
            entries.add(new CptRagResult.CptEntry(code, code, "medium"));
        }
        return entries.isEmpty() ? CptRagResult.error("No codes in text") : CptRagResult.ok(entries);
    }

    private String compactClinicalJson(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            String s = mapper.writeValueAsString(root);
            if (s.length() > 8000) {
                return s.substring(0, 8000) + "…";
            }
            return s;
        } catch (Exception e) {
            log.warn("output.json is not valid JSON; using raw excerpt for CPT context: {}", e.getMessage());
            String t = json == null ? "" : json.trim();
            return t.length() > 8000 ? t.substring(0, 8000) + "…" : t;
        }
    }

    private Path resolveCptCodesPath() {
        Path a = resourcesDir.resolve("cpt-codes.json");
        Path b = resourcesDir.resolve("cpt-Codes.json");
        if (Files.exists(a)) return a;
        if (Files.exists(b)) return b;
        return a;
    }

    private Path resolveGuidelinesPath() {
        Path a = resourcesDir.resolve("cpt-guidelines.txt");
        Path b = resourcesDir.resolve("cpt-guidlines.txt");
        if (Files.exists(a)) return a;
        if (Files.exists(b)) return b;
        return b;
    }
}
