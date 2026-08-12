package wl.ai.ragICD;

/**
 * Result of a RAG code lookup (ICD or procedure).
 */
public record RagLookupResult(
        boolean success,
        String code,
        String description,
        String confidence,
        String errorMessage
) {
    public static RagLookupResult icd(String code, String description, String confidence) {
        return new RagLookupResult(true, code, description, confidence, null);
    }

    public static RagLookupResult procedure(String code, String description, String confidence) {
        return new RagLookupResult(true, code, description, confidence, null);
    }

    public static RagLookupResult error(String message) {
        return new RagLookupResult(false, null, null, null, message);
    }

    public static RagLookupResult fallback(String rawText, String query) {
        return new RagLookupResult(false, null, rawText, null,
                "Could not parse LLM response. Raw: " + (rawText != null ? rawText.substring(0, Math.min(200, rawText.length())) : ""));
    }
}
