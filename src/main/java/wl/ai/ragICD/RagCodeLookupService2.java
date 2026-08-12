package wl.ai.ragICD;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import wl.ai.LLMService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RagCodeLookupService2 {

    private static final Logger log = LoggerFactory.getLogger(RagCodeLookupService.class);

    private static final int TOP_K = 5;
    private static final int MAX_CONTEXT = 5;

    private String masterDataPath = "";
    private String procedureDataPath = "";
    private LLMService llmService = new LLMService();
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<IndexedEntry> allEntries = new ArrayList<>();
    private volatile boolean loaded;

    public RagCodeLookupService2() {
        this("resources/masterData.json", "resources/procedureData.json");
    }

    public RagCodeLookupService2(String masterDataPath, String procedureDataPath) {
        this.masterDataPath = masterDataPath;
        this.procedureDataPath = procedureDataPath;
        this.llmService = new LLMService();
    }

    public synchronized void ensureLoaded() throws IOException {
        if (loaded) return;

        loadData(masterDataPath, "icd");
        loadData(procedureDataPath, "procedure");

        loaded = true;
        log.info("Loaded total entries: {}", allEntries.size());
    }

    private void loadData(String path, String type) throws IOException {
        File file = resolveFile(path);
        if (!file.exists()) {
            log.warn("{} file not found: {}", type, path);
            return;
        }

        List<Map<String, Object>> list = mapper.readValue(file,
                mapper.getTypeFactory().constructCollectionType(List.class, Map.class));

        for (Map<String, Object> entry : list) {
            IndexedEntry indexed = toIndexedEntry(entry, type);
            if (indexed != null) {
                allEntries.add(indexed);
            }
        }

        log.info("Loaded {} {} entries", allEntries.size(), type);
    }

    private IndexedEntry toIndexedEntry(Map<String, Object> map, String type) {
        String code = getStr(map, "code");
        if (code == null || code.isBlank()) return null;

        String desc = getStr(map, "desc");

        String searchable = String.join(" ",
                nullToEmpty(desc),
                nullToEmpty(getStr(map, "desc_short")),
                nullToEmpty(getStr(map, "clinical_responsibility")),
                nullToEmpty(getStr(map, "layterm")),
                nullToEmpty(getStr(map, "procedure"))
        ).toLowerCase();

        try {
            List<Double> embedding = llmService.getEmbedding(searchable);
            return new IndexedEntry(code, desc, searchable, type, embedding);
        } catch (Exception e) {
            log.warn("Embedding failed for {}: {}", code, e.getMessage());
            return null;
        }
    }

    public RagLookupResult findICD(String description) {
        return findCode(description, "icd");
    }

    public RagLookupResult findProcedure(String description) {
        return findCode(description, "procedure");
    }

    private RagLookupResult findCode(String query, String type) {
        try {
            ensureLoaded();
        } catch (IOException e) {
            return RagLookupResult.error("Data load failed: " + e.getMessage());
        }

        if (query == null || query.isBlank()) {
            return RagLookupResult.error("Query cannot be empty");
        }

        List<IndexedEntry> candidates = retrieve(query, type);

        if (candidates.isEmpty()) {
            return RagLookupResult.error("No matches found");
        }

        String context = buildContext(candidates);

        String systemPrompt = "You are a medical coding expert.\n" +
                "You MUST choose ONLY from the provided entries.\n" +
                "Do NOT guess outside list.\n" +
                "Return ONLY JSON.";

        String userPrompt = "Query: " + query + "\n\nEntries:\n" + context +
                "\n\nReturn JSON:\n" +
                "{\"code\":\"...\",\"description\":\"...\",\"confidence\":\"high|medium|low\"}";

        try {
            String raw = llmService.call(systemPrompt, userPrompt, 512, 0.1);
            return parseResponse(raw, query, type);
        } catch (Exception e) {
            return RagLookupResult.error("LLM error: " + e.getMessage());
        }
    }

    private List<IndexedEntry> retrieve(String query, String type) {
        try {
            List<Double> queryEmbedding = llmService.getEmbedding(query);

            return allEntries.stream()
                    .filter(e -> type.equals(e.type))
                    .map(e -> Map.entry(e, cosineSimilarity(queryEmbedding, e.embedding)))
                    .sorted(Map.Entry.<IndexedEntry, Double>comparingByValue().reversed())
                    .limit(TOP_K)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildContext(List<IndexedEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (IndexedEntry e : entries) {
            sb.append("- ").append(e.code).append(": ").append(e.desc).append("\n");
        }
        return sb.toString();
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        double dot = 0, normA = 0, normB = 0;

        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }

        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private RagLookupResult parseResponse(String raw, String query, String type) {
        Map<String, Object> parsed = llmService.parseToMap(raw);

        String code = getStr(parsed, "code");
        String desc = getStr(parsed, "description");
        String confidence = getStr(parsed, "confidence");

        if (code != null) {
            if ("icd".equals(type)) {
                return RagLookupResult.icd(code, desc, confidence);
            } else {
                return RagLookupResult.procedure(code, desc, confidence);
            }
        }

        return RagLookupResult.fallback(raw, query);
    }

    private File resolveFile(String path) {
        Path p = Path.of(path);
        if (!p.toFile().exists()) {
            p = Path.of(System.getProperty("user.dir", "."), path);
        }
        return p.toFile();
    }

    private static String getStr(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static class IndexedEntry {
        final String code, desc, searchable, type;
        final List<Double> embedding;

        IndexedEntry(String code, String desc, String searchable, String type, List<Double> embedding) {
            this.code = code;
            this.desc = desc;
            this.searchable = searchable;
            this.type = type;
            this.embedding = embedding;
        }
    }
}