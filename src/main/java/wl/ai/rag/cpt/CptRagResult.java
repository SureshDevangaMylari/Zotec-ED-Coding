package wl.ai.rag.cpt;

import java.util.List;

/**
 * Result of CPT RAG lookup (one or more CPT codes).
 */
public record CptRagResult(boolean success, List<CptEntry> cptCodes, String errorMessage) {

    public record CptEntry(String code, String description, String confidence) {}

    public static CptRagResult ok(List<CptEntry> codes) {
        return new CptRagResult(true, codes, null);
    }

    public static CptRagResult error(String message) {
        return new CptRagResult(false, List.of(), message);
    }
}
