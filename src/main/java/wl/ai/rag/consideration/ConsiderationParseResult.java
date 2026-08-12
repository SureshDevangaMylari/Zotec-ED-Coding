package wl.ai.rag.consideration;

import java.util.List;

/**
 * Parsed consideration document: optional client name from the document header plus table rows.
 *
 * @param payorColumnLabels header captions from the billing table (e.g. {@code Medicare} vs {@code MCA});
 *                          {@code null} for .txt/.pdf or when no header row was detected.
 */
public record ConsiderationParseResult(
        String clientNameFromHeader,
        String clientKeyFromFilename,
        List<ConsiderationCodingRow> rows,
        String supplementalPlainText,
        PayorColumnLabels payorColumnLabels) {

    public ConsiderationParseResult {
        supplementalPlainText = supplementalPlainText == null ? "" : supplementalPlainText;
    }

    /**
     * Primary client identifier for search and storage: header name when present, otherwise the key from the filename.
     */
    public String effectiveClientId() {
        if (clientNameFromHeader != null && !clientNameFromHeader.isBlank()) {
            return clientNameFromHeader.trim();
        }
        return clientKeyFromFilename != null ? clientKeyFromFilename : "";
    }

    /**
     * True if {@code userInput} matches header, filename key, or {@link #effectiveClientId()} using
     * {@link ConsiderationClientKeys#matchesLoose(String, String)} (punctuation / LLP / minor wording differences).
     */
    public boolean matchesClientQuery(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        if (ConsiderationClientKeys.matchesLoose(effectiveClientId(), userInput)) {
            return true;
        }
        if (ConsiderationClientKeys.matchesLoose(clientKeyFromFilename(), userInput)) {
            return true;
        }
        if (clientNameFromHeader != null && ConsiderationClientKeys.matchesLoose(clientNameFromHeader, userInput)) {
            return true;
        }
        return false;
    }

    /**
     * Same as {@link #matchesClientQuery(String)} plus matching against the original filename (and stem), so
     * queries like {@code CARSON TAHOE} still find {@code ...Carson...100824.docx} when the header was not parsed.
     * <p>
     * Also matches when every significant word in {@code userInput} appears somewhere in {@link #searchableText()} —
     * e.g. client name only in a table cell or comment while the file is keyed under another name.
     */
    public boolean matchesClientQuery(String userInput, String originalFilename) {
        if (matchesClientQuery(userInput)) {
            return true;
        }
        if (originalFilename == null || originalFilename.isBlank()) {
            return ConsiderationClientKeys.allQueryTokensFoundInHaystack(searchableText(), userInput);
        }
        if (ConsiderationClientKeys.matchesLoose(originalFilename, userInput)) {
            return true;
        }
        String stem = originalFilename.replaceAll("(?i)\\.(docx|doc|pdf|txt)$", "");
        if (ConsiderationClientKeys.matchesLoose(stem, userInput)) {
            return true;
        }
        return ConsiderationClientKeys.allQueryTokensFoundInHaystack(searchableText(), userInput);
    }

    /**
     * Text from header, file key, and all parsed table fields — used for in-document name hints.
     */
    public String searchableText() {
        StringBuilder sb = new StringBuilder(4096);
        if (clientNameFromHeader != null && !clientNameFromHeader.isBlank()) {
            sb.append(clientNameFromHeader).append('\n');
        }
        if (clientKeyFromFilename != null && !clientKeyFromFilename.isBlank()) {
            sb.append(clientKeyFromFilename).append('\n');
        }
        for (ConsiderationCodingRow r : rows) {
            sb.append(r.section()).append(' ')
                    .append(r.billingIssue()).append(' ')
                    .append(r.medicare()).append(' ')
                    .append(r.medicaid()).append(' ')
                    .append(r.bcbs()).append(' ')
                    .append(r.hmos()).append(' ')
                    .append(r.otherPayors()).append(' ')
                    .append(r.comments()).append('\n');
        }
        if (!supplementalPlainText.isBlank()) {
            sb.append('\n').append(supplementalPlainText);
        }
        return sb.toString();
    }
}
