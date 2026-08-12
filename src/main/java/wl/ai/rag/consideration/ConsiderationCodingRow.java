package wl.ai.rag.consideration;

/**
 * One row from a "Coding Considerations" billing table (main or Physician Extenders subsection).
 */
public record ConsiderationCodingRow(
        String section,
        String billingIssue,
        String medicare,
        String medicaid,
        String bcbs,
        String hmos,
        String otherPayors,
        String comments) {

    public String toEmbedText() {
        return String.join(" | ",
                "section: " + nullToEmpty(section),
                "billing_issue: " + nullToEmpty(billingIssue),
                "medicare: " + nullToEmpty(medicare),
                "medicaid: " + nullToEmpty(medicaid),
                "bc_bs: " + nullToEmpty(bcbs),
                "hmos: " + nullToEmpty(hmos),
                "other_payors: " + nullToEmpty(otherPayors),
                "comments: " + nullToEmpty(comments));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
