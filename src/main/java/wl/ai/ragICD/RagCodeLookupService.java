package wl.ai.ragICD;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wl.ai.LLMService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

/**
 * RAG-based medical code lookup service using Qwen model.
 * Data source: resources/masterData.json (ICD codes) and optional procedureData.json (CPT/HCPCS).
 * Description to ICD code (e.g., "cholera" to A00.9).
 * Clinical text to Procedure code (e.g., "chest x-ray" to 71046).
 */
@Service
public class RagCodeLookupService {

    private static final Logger log = LoggerFactory.getLogger(RagCodeLookupService.class);
    private static final int MAX_RETRIEVAL = 30;
    private static final int MAX_CONTEXT_ENTRIES = 20;

    private final String masterDataPath;
    private final String procedureDataPath;
    private final LLMService llmService;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, List<IndexedEntry>> keywordIndex = new ConcurrentHashMap<>();
    private final List<IndexedEntry> procedureEntries = new ArrayList<>();
    private volatile boolean loaded;

    public RagCodeLookupService() {
        this("resources/masterData.json", "resources/procedureData.json");
    }

    public RagCodeLookupService(String masterDataPath, String procedureDataPath) {
        this.masterDataPath = masterDataPath;
        this.procedureDataPath = procedureDataPath;
        this.llmService = new LLMService();
    }

    public RagCodeLookupService(String masterDataPath, String procedureDataPath, LLMService llmService) {
        this.masterDataPath = masterDataPath;
        this.procedureDataPath = procedureDataPath;
        this.llmService = llmService != null ? llmService : new LLMService();
    }

    /**
     * Ensures master data is loaded (lazy load).
     */
    public synchronized void ensureLoaded() throws IOException {
        if (loaded) return;
        loadMasterData();
        loadProcedureData();
        loaded = true;
    }

    private void loadMasterData() throws IOException {
        File file = resolveFile(masterDataPath);
        if (!file.exists()) {
            log.warn("Master data file not found: {}", file.getAbsolutePath());
            return;
        }

        log.info("Loading master data from {} (streaming)...", file.getAbsolutePath());
        ObjectReader reader = mapper.readerFor(Map.class);

        try (var parser = mapper.createParser(file)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                log.warn("Expected JSON array in masterData.json");
                return;
            }

            int count = 0;
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = reader.readValue(parser);
                    IndexedEntry indexed = toIndexedEntry(entry, "icd");
                    if (indexed != null) {
                        indexEntry(indexed);
                        count++;
                    }
                } catch (Exception e) {
                    log.debug("Skip entry: {}", e.getMessage());
                }
            }
            log.info("Indexed {} ICD entries from masterData.json", count);
        }
    }

    private void loadProcedureData() throws IOException {
        File file = resolveFile(procedureDataPath);
        if (!file.exists()) {
            log.info("Procedure data not found at {} - procedure lookup will use LLM with ICD context only", procedureDataPath);
            return;
        }

        log.info("Loading procedure data from {}", file.getAbsolutePath());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = mapper.readValue(file,
                mapper.getTypeFactory().constructCollectionType(List.class, Map.class));

        for (Map<String, Object> entry : list) {
            IndexedEntry indexed = toIndexedEntry(entry, "procedure");
            if (indexed != null) {
                indexEntry(indexed);
                procedureEntries.add(indexed);
            }
        }
        log.info("Indexed {} procedure entries", procedureEntries.size());
    }

    private IndexedEntry toIndexedEntry(Map<String, Object> map, String type) {
        String code = getStr(map, "code");
        if (code == null || code.isBlank()) return null;

        String desc = getStr(map, "desc");
        String descShort = getStr(map, "desc_short");
        String clinical = getStr(map, "clinical_responsibility");
        String layterm = getStr(map, "layterm");
        String procedure = getStr(map, "procedure"); // for procedure entries

        String searchable = String.join(" ", Arrays.asList(
                nullToEmpty(desc), nullToEmpty(descShort),
                nullToEmpty(clinical), nullToEmpty(layterm), nullToEmpty(procedure)
        )).toLowerCase();

        return new IndexedEntry(code, desc, searchable, type);
    }

    private void indexEntry(IndexedEntry entry) {
        Set<String> tokens = tokenize(entry.searchable);
        for (String t : tokens) {
            if (t.length() < 2) continue;
            keywordIndex.computeIfAbsent(t, k -> new ArrayList<>()).add(entry);
        }
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase()
                        .replaceAll("[^a-z0-9\\s-]", " ")
                        .split("\\s+"))
                .filter(s -> s.length() >= 2)
                .collect(Collectors.toSet());
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

    /**
     * Find ICD code from description. Accepts one parameter: description.
     *
     * @param description diagnosis/condition description (e.g. "chest pain", "diabetes")
     * @return RagLookupResult with ICD code and description
     */
    public RagLookupResult findICD(String description) {
        return findIcdCode(description);
    }

    /**
     * Find procedure code from clinical text. Accepts one parameter: description.
     *
     * @param description procedure/clinical text (e.g. "chest x-ray", "blood draw")
     * @return RagLookupResult with procedure code(s) and description
     */
    public RagLookupResult findProcedure(String description) {
        return findProcedureCode(description);
    }

    /**
     * RAG: Given a description, retrieve relevant entries and ask Qwen for the best ICD code.
     */
    public RagLookupResult findIcdCode(String description) {
        try {
            ensureLoaded();
        } catch (IOException e) {
            return RagLookupResult.error("Failed to load data: " + e.getMessage());
        }

        if (description == null || description.isBlank()) {
            return RagLookupResult.error("Description cannot be empty");
        }

        List<IndexedEntry> candidates = retrieve(description, "icd");
        if (candidates.isEmpty()) {
            return RagLookupResult.error("No matching ICD entries found for: " + description);
        }

        String systemPrompt = buildIcdSystemPrompt();
        String context = buildContext(candidates, MAX_CONTEXT_ENTRIES);
        String userPrompt = String.format(
                "## User's exact description: \"%s\"\n\n## ICD entries (pick the one that matches the user's description):\n%s\n\n" +
                "Return ONLY valid JSON: {\"icd_code\": \"...\", \"description\": \"...\", \"confidence\": \"high|medium|low\"}. " +
                "Select the code whose description matches the user's description. For 'chest pain' choose R07.x chest pain codes, NOT lung/respiratory codes.",
                description.trim(), context
        );

        try {
            String raw = llmService.call(systemPrompt, userPrompt, 512, 0.1);
            return parseIcdResponse(raw, description);
        } catch (IOException e) {
            log.warn("LLM call failed: {}", e.getMessage());
            return RagLookupResult.error("LLM error: " + e.getMessage());
        }
    }

    /**
     * RAG: Given clinical text, retrieve relevant entries and ask Qwen for procedure code(s).
     */
    public RagLookupResult findProcedureCode(String text) {
        try {
            ensureLoaded();
        } catch (IOException e) {
            return RagLookupResult.error("Failed to load data: " + e.getMessage());
        }

        if (text == null || text.isBlank()) {
            return RagLookupResult.error("Text cannot be empty");
        }

        List<IndexedEntry> candidates = retrieve(text, "procedure");
        if (candidates.isEmpty()) {
            candidates = retrieve(text, "icd"); // fallback to ICD context for procedure inference
        }

        String systemPrompt = buildProcedureSystemPrompt();
        String context = buildContext(candidates, MAX_CONTEXT_ENTRIES);
        String userPrompt = String.format(
                "## Clinical text / procedure description:\n%s\n\n## Relevant reference entries:\n%s\n\n" +
                "Return ONLY a JSON object: {\"procedure_code\": \"...\", \"procedure_description\": \"...\", \"confidence\": \"high|medium|low\"}. " +
                "For multiple procedures, use: {\"procedure_codes\": [{\"code\": \"...\", \"description\": \"...\"}], \"confidence\": \"...\"}.",
                text.trim(), context
        );

        try {
            String raw = llmService.call(systemPrompt, userPrompt, 1024, 0.1);
            return parseProcedureResponse(raw, text);
        } catch (IOException e) {
            log.warn("LLM call failed: {}", e.getMessage());
            return RagLookupResult.error("LLM error: " + e.getMessage());
        }
    }

    private List<IndexedEntry> retrieve(String query, String typeFilter) {
        Set<String> queryTokens = tokenize(query);
        Map<IndexedEntry, Integer> scores = new HashMap<>();
        String queryNorm = query.trim().toLowerCase();

        for (String token : queryTokens) {
            List<IndexedEntry> entries = keywordIndex.get(token);
            if (entries == null) continue;
            for (IndexedEntry e : entries) {
                if (typeFilter != null && !typeFilter.equals(e.type)) continue;
                int score = 1;
                // Strong boost: query appears as phrase in primary description
                if (e.desc != null && e.desc.toLowerCase().contains(queryNorm)) {
                    score += 100;
                }
                scores.merge(e, score, Integer::sum);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<IndexedEntry, Integer>comparingByValue().reversed())
                .limit(MAX_RETRIEVAL)
                .map(Map.Entry::getKey)
                .distinct()
                .toList();
    }

    private String buildContext(List<IndexedEntry> entries, int max) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (IndexedEntry e : entries) {
            if (n >= max) break;
            String key = e.code + "|" + e.desc;
            if (seen.add(key)) {
                sb.append("- ").append(e.code).append(": ").append(e.desc).append("\n");
                n++;
            }
        }
        return sb.toString();
    }

    private String buildIcdSystemPrompt() {
        return "You are a medical coding expert. Given the user's EXACT description and a list of ICD-10 entries, " +
                "select the ONE entry that best matches the user's description. " +
                "CRITICAL: If the user says 'chest pain', select the code for CHEST PAIN (R07.x), NOT lung disease, pneumonia, or other conditions that merely mention 'chest'. " +
                "Match the primary diagnosis the user describes. Return only valid JSON.";
    }

    private String buildProcedureSystemPrompt() {
        return "You are a medical coding expert. Given clinical text describing procedures and reference entries, " +
                "identify the most appropriate procedure code(s) (CPT/HCPCS). Return only valid JSON.";
    }

    private RagLookupResult parseIcdResponse(String raw, String query) {
        Map<String, Object> parsed = llmService.parseToMap(raw);
        Object rawObj = parsed.get("raw");
        if (rawObj != null) {
            return RagLookupResult.fallback(rawObj.toString(), query);
        }

        String code = getStr(parsed, "icd_code");
        String desc = getStr(parsed, "description");
        String confidence = getStr(parsed, "confidence");

        if (code != null && !code.isBlank()) {
            return RagLookupResult.icd(code, desc != null ? desc : code, confidence);
        }
        return RagLookupResult.fallback(raw, query);
    }

    private RagLookupResult parseProcedureResponse(String raw, String query) {
        Map<String, Object> parsed = llmService.parseToMap(raw);
        Object rawObj = parsed.get("raw");
        if (rawObj != null) {
            return RagLookupResult.fallback(rawObj.toString(), query);
        }

        String singleCode = getStr(parsed, "procedure_code");
        if (singleCode != null && !singleCode.isBlank()) {
            String desc = getStr(parsed, "procedure_description");
            String confidence = getStr(parsed, "confidence");
            return RagLookupResult.procedure(singleCode, desc != null ? desc : singleCode, confidence);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> codes = (List<Map<String, Object>>) parsed.get("procedure_codes");
        if (codes != null && !codes.isEmpty()) {
            List<String> codeList = new ArrayList<>();
            for (Map<String, Object> m : codes) {
                String c = getStr(m, "code");
                String d = getStr(m, "description");
                if (c != null) codeList.add(c + (d != null ? " (" + d + ")" : ""));
            }
            String confidence = getStr(parsed, "confidence");
            return RagLookupResult.procedure(String.join(", ", codeList), query, confidence);
        }

        return RagLookupResult.fallback(raw, query);
    }

    private static final class IndexedEntry {
        final String code, desc, searchable, type;
        IndexedEntry(String code, String desc, String searchable, String type) {
            this.code = code;
            this.desc = desc;
            this.searchable = searchable;
            this.type = type;
        }
    }
}
