package wl.ai.rag.consideration;

/**
 * Payor column titles as they appear in the source table header row (e.g. {@code Medicare} vs {@code MCA}).
 * Used as JSON object keys for per-payor yes/no maps.
 */
public record PayorColumnLabels(String medicare, String medicaid, String bcBs, String hmos, String others) {

    /** Keys used when the format has no header row (e.g. plain .txt). Matches prior CLI output. */
    public static PayorColumnLabels legacyJsonKeys() {
        return new PayorColumnLabels("mca", "medicaid", "BC/BS", "HMO's", "Others");
    }

    /**
     * Reads trimmed header cell text for each logical column using {@code colMap} (see
     * {@link ConsiderationDocParser}).
     */
    public static PayorColumnLabels fromHeaderRow(String[] headerCells, int[] colMap) {
        if (headerCells == null || colMap == null || colMap.length < 6) {
            return null;
        }
        return new PayorColumnLabels(
                cell(headerCells, colMap[1]),
                cell(headerCells, colMap[2]),
                cell(headerCells, colMap[3]),
                cell(headerCells, colMap[4]),
                cell(headerCells, colMap[5]));
    }

    private static String cell(String[] headerCells, int idx) {
        if (idx < 0 || idx >= headerCells.length) {
            return null;
        }
        String t = headerCells[idx].trim();
        return t.isEmpty() ? null : t;
    }

    /** Merge with {@link #legacyJsonKeys()} for any null/blank label. */
    public PayorColumnLabels withFallbacks() {
        PayorColumnLabels d = legacyJsonKeys();
        return new PayorColumnLabels(
                coalesce(medicare, d.medicare),
                coalesce(medicaid, d.medicaid),
                coalesce(bcBs, d.bcBs),
                coalesce(hmos, d.hmos),
                coalesce(others, d.others));
    }

    private static String coalesce(String a, String b) {
        return a != null && !a.isBlank() ? a.trim() : b;
    }
}
