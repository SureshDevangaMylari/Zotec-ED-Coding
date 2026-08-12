package wl.ai.rag.consideration;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a stable client key from filenames, including:
 * <ul>
 *   <li>{@code Coding Considerations SLMD_....docx} → id after the prefix</li>
 *   <li>{@code CTEP - Nevada Coding Considerations - 100824.docx} → compact {@code CTEP - Nevada - 100824}
 *       (middle {@code Coding Considerations} removed so keys stay short; substring match still finds
 *       {@code Nevada}, {@code CTEP}, {@code 100824})</li>
 * </ul>
 * When the document contains a {@code Client Name} header, {@link ConsiderationParseResult#effectiveClientId()}
 * prefers that value for identity and search.
 */
public final class ConsiderationClientKeys {

    private static final Pattern LEADING_PREFIX = Pattern.compile("(?i)^Coding\\s+Considerations\\s+");
    private static final Pattern EXTENSION = Pattern.compile("\\.(docx|doc|pdf|txt)$", Pattern.CASE_INSENSITIVE);
    /**
     * {@code ... Something - Nevada Coding Considerations - 100824} → groups (prefix before "Coding Considerations - ", date/suffix).
     */
    private static final Pattern CODING_CONSIDERATIONS_MIDDLE = Pattern.compile(
            "(?i)^(.+?)\\s+Coding\\s+Considerations\\s*-\\s*(.+)$");

    private ConsiderationClientKeys() {}

    public static String fromFilename(String filename) {
        if (filename == null || filename.isBlank()) return "";
        String n = EXTENSION.matcher(filename).replaceFirst("");
        n = LEADING_PREFIX.matcher(n).replaceFirst("");
        n = n.trim();
        Matcher m = CODING_CONSIDERATIONS_MIDDLE.matcher(n);
        if (m.matches()) {
            String left = m.group(1).trim();
            String right = m.group(2).trim();
            if (!left.isEmpty() && !right.isEmpty()) {
                return left + " - " + right;
            }
        }
        return n;
    }

    /** Normalize for comparison: lower case, collapse spaces. */
    public static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /**
     * True if {@code userInput} selects this client: exact normalized match, or substring either way
     * (e.g. user passes "HAWKINSVILLE" or full "SLMD_HAWKINSVILLE-TAYLOR-01092024").
     */
    public static boolean matches(String storedClientId, String userInput) {
        String a = normalize(storedClientId);
        String b = normalize(userInput);
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    /**
     * Keeps letters and digits only (drops punctuation, commas, hyphens become word breaks), lower case, single spaces.
     * Use for comparing names like {@code CARSON TAHOE EMERGENCY PHYSICIANS LLP} vs
     * {@code Carson Tahoe Emergency Physicians} in the document.
     */
    public static String normalizeLoose(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT);
        t = t.replaceAll("[^a-z0-9]+", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /**
     * Like {@link #matches} but tolerates punctuation, extra suffixes (LLP, Inc), and matches on
     * significant word overlap when substrings differ (e.g. header omits "LLP").
     */
    public static boolean matchesLoose(String stored, String userInput) {
        if (stored == null || userInput == null) return false;
        if (matches(stored, userInput)) return true;
        String a = normalizeLoose(stored);
        String b = normalizeLoose(userInput);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (a.contains(b) || b.contains(a)) return true;
        Set<String> ta = tokenSet(a);
        Set<String> tb = tokenSet(b);
        if (ta.isEmpty() || tb.isEmpty()) return false;
        int inter = 0;
        for (String w : ta) {
            if (tb.contains(w)) {
                inter++;
            }
        }
        int min = Math.min(ta.size(), tb.size());
        int max = Math.max(ta.size(), tb.size());
        if (min == 0) return false;
        double coverShort = (double) inter / min;
        // Short queries: both words must appear (e.g. "carson tahoe")
        if (min <= 2) {
            return inter == min;
        }
        // Long names: shared words + coverage on the shorter side
        return inter >= 3 && coverShort >= 0.55 && inter >= (max >= 6 ? 4 : 3);
    }

    /**
     * True if every significant token in {@code query} appears as a whole word in {@code haystack} (after
     * {@link #normalizeLoose(String)}). Use when the client name appears only inside the document body, not in the
     * filename or {@code Client Name} header.
     */
    public static boolean allQueryTokensFoundInHaystack(String haystack, String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String h = normalizeLoose(haystack);
        String q = normalizeLoose(query);
        if (h.isEmpty() || q.isEmpty()) {
            return false;
        }
        Set<String> qWords = tokenSet(q);
        if (qWords.isEmpty()) {
            return false;
        }
        Set<String> hWords = tokenSet(h);
        for (String w : qWords) {
            if (!hWords.contains(w)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> tokenSet(String normalizeLoose) {
        String[] parts = normalizeLoose.split(" ");
        Set<String> s = new HashSet<>();
        for (String p : parts) {
            if (p.length() >= 2) {
                s.add(p);
            }
        }
        return s;
    }
}
