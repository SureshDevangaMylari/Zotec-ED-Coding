package wl.ai.ragICD;

import java.util.List;

/**
 * Result of RAG alpha lookup: multiple ICD codes from a sentence.
 * Used when one sentence contains multiple diagnoses (e.g. "Amphetamine use disorder, severe; Obesity").
 */
public record RagAlphaResult(
        boolean success,
        List<IcdEntry> icdCodes,
        String errorMessage
) {
    public static RagAlphaResult ok(List<IcdEntry> codes) {
        return new RagAlphaResult(true, codes != null ? codes : List.of(), null);
    }

    public static RagAlphaResult error(String message) {
        return new RagAlphaResult(false, List.of(), message);
    }

    public record IcdEntry(String code, String description, String confidence) {}
}
