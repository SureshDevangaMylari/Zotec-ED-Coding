package wl.ai.rag.RAG_Coding;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Programmatic walk of {@code icdalpha_2026.json} for common substance-use paths.
 *
 * <p>If the clinical text contains the word {@code use} or {@code with}, navigation uses the
 * {@code Use (of)} chain only: substance row → {@code See} → target term → subterms if documentation
 * matches; otherwise the default code on the resolved line (e.g. stimulant NEC → F15.90).
 *
 * <p>If the text does <strong>not</strong> contain {@code use} or {@code with} as words, and the text
 * looks like amphetamine-family + use disorder + severity, the index main term
 * {@code amphetamine-type substance use} → mild / moderate / severe (F15.10 / F15.20) may be tried
 * first; otherwise fall back to the {@code Use (of)} chain when a substance hint is available.
 */
public final class IcdAlphaIndexTraversalService {

    /** e.g. "Amphetamine use disorder" → amphetamine (word before "use" as main term). */
    private static final Pattern SUBSTANCE_BEFORE_USE =
            Pattern.compile("\\b([a-z]{3,})\\s+use\\b", Pattern.CASE_INSENSITIVE);

    /** When query contains these words, follow {@code Use (of)} only (skip amphetamine-type shortcut). */
    private static final Pattern WORD_USE = Pattern.compile("\\buse\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_WITH = Pattern.compile("\\bwith\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern AMPHETAMINE_FAMILY =
            Pattern.compile("\\b(methamphetamine|amphetamine)s?\\b", Pattern.CASE_INSENSITIVE);

    private static final String AMPHETAMINE_TYPE_SUBSTANCE_USE_TITLE = "amphetamine-type substance use";

    private final ObjectMapper mapper = new ObjectMapper();
    private volatile List<Map<String, Object>> rootsCache;
    /** Cached; large tree — resolved once per JVM. */
    private volatile Map<String, Object> amphetamineTypeSubstanceUseNodeCache;

    public record TraversalResult(String code, List<String> pathTitles, String explanation) {}

    /**
     * When the sentence looks like substance + use (e.g. "Amphetamine use disorder, severe"),
     * finds {@code (of)} → substance row → follows {@code See} → picks deepest matching sub-code
     * or the parent line code if no subterm matches.
     */
    public Optional<TraversalResult> tryResolveUseOfChain(String clinicalText) {
        if (clinicalText == null || clinicalText.isBlank()) return Optional.empty();
        String text = clinicalText.trim();
        String low = text.toLowerCase(Locale.ROOT);
        String substanceHint = extractSubstanceHint(low);
        if (substanceHint == null) return Optional.empty();

        try {
            List<Map<String, Object>> roots = loadRoots();
            if (roots.isEmpty()) return Optional.empty();

            // Only when the query does NOT signal "use/with" phrasing: allow amphetamine-type substance use
            // (severity rows). If "use" or "with" appears as words, always use Use (of) chain below.
            if (!queryIndicatesUseWithPath(low)) {
                Optional<TraversalResult> amphetamineType = tryAmphetamineTypeUseDisorderSeverity(low, substanceHint, roots);
                if (amphetamineType.isPresent()) return amphetamineType;
            }

            // Many unrelated index lines are titled "(of)"; pick the "(of)" block that contains
            // a substance row matching this sentence (e.g. Use (of) → amphetamines).
            Map<String, Object> ofNode = null;
            Map<String, Object> substanceRow = null;
            for (Map<String, Object> root : roots) {
                if (!"(of)".equals(str(root.get("title")))) continue;
                Map<String, Object> row = findSubstanceRowUnder(root, substanceHint);
                if (row != null) {
                    ofNode = root;
                    substanceRow = row;
                    break;
                }
            }
            if (ofNode == null || substanceRow == null) return Optional.empty();

            List<String> path = new ArrayList<>();
            path.add("(of)");
            String subTitle = str(substanceRow.get("title"));
            if (subTitle != null) path.add(stripSeeInstruction(subTitle));

            Map<String, Object> anchor = substanceRow;
            String see = str(substanceRow.get("see"));
            if (see != null && !see.isBlank()) {
                Map<String, Object> resolved = resolveSeeChain(roots, see, ofNode);
                if (resolved == null) return Optional.empty();
                anchor = resolved;
                String at = str(anchor.get("title"));
                if (at != null) path.add(stripSeeInstruction(at));
            }

            String code = resolveBestMatchingCode(anchor, low, roots);
            if (code == null || code.isBlank()) return Optional.empty();

            String explain = "Use (of) alphabetic index path: "
                    + String.join(" → ", path)
                    + ". Subterms apply only if the documentation matches that index line; otherwise use the code on the resolved main line (then confirm digits in Tabular).";
            return Optional.of(new TraversalResult(code, path, explain));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Only when the query has no {@code use}/{@code with} word: amphetamine-family + disorder + severity
     * → index {@code amphetamine-type substance use} → mild / moderate / severe.
     */
    private Optional<TraversalResult> tryAmphetamineTypeUseDisorderSeverity(String clinicalLower,
                                                                            String substanceHint,
                                                                            List<Map<String, Object>> roots) {
        if (!isAmphetamineFamilySubstance(substanceHint)) return Optional.empty();
        if (!clinicalLower.contains("disorder")) return Optional.empty();
        String severityKey = detectSeverityKeyword(clinicalLower);
        if (severityKey == null) return Optional.empty();

        Map<String, Object> hub = getAmphetamineTypeSubstanceUseNode(roots);
        if (hub == null) return Optional.empty();

        Map<String, Object> sevRow = findChildByTitleToken(hub, severityKey);
        if (sevRow == null) return Optional.empty();
        String code = str(sevRow.get("code"));
        if (code == null || code.isBlank()) return Optional.empty();

        List<String> path = new ArrayList<>();
        path.add(AMPHETAMINE_TYPE_SUBSTANCE_USE_TITLE);
        path.add(severityKey);
        String explain = "Index main term " + AMPHETAMINE_TYPE_SUBSTANCE_USE_TITLE + " (query had no \"use\"/\"with\" word; "
                + substanceHint + " + disorder + " + severityKey + ") → subterm \"" + severityKey
                + "\". Confirm in Tabular List (F15.10 mild; F15.20 moderate or severe per ICD-10-CM).";
        return Optional.of(new TraversalResult(code, path, explain));
    }

    private static boolean isAmphetamineFamilySubstance(String substanceHint) {
        if (substanceHint == null) return false;
        String h = substanceHint.toLowerCase(Locale.ROOT);
        return h.contains("amphetamine") || h.contains("methamphetamine");
    }

    /** mild / moderate / severe — first match wins if multiple appear (rare). */
    private static String detectSeverityKeyword(String clinicalLower) {
        if (clinicalLower.contains("severe")) return "severe";
        if (clinicalLower.contains("moderate")) return "moderate";
        if (clinicalLower.contains("mild")) return "mild";
        return null;
    }

    private Map<String, Object> getAmphetamineTypeSubstanceUseNode(List<Map<String, Object>> roots) {
        Map<String, Object> c = amphetamineTypeSubstanceUseNodeCache;
        if (c != null) return c;
        synchronized (this) {
            if (amphetamineTypeSubstanceUseNodeCache != null) return amphetamineTypeSubstanceUseNodeCache;
            amphetamineTypeSubstanceUseNodeCache = dfsFindNodeWithExactStripTitle(roots, AMPHETAMINE_TYPE_SUBSTANCE_USE_TITLE);
            return amphetamineTypeSubstanceUseNodeCache;
        }
    }

    private Map<String, Object> dfsFindNodeWithExactStripTitle(List<Map<String, Object>> roots, String target) {
        for (Map<String, Object> n : roots) {
            Map<String, Object> f = dfsFindNodeWithExactStripTitle(n, target);
            if (f != null) return f;
        }
        return null;
    }

    private Map<String, Object> dfsFindNodeWithExactStripTitle(Map<String, Object> node, String target) {
        String t = str(node.get("title"));
        if (t != null && stripSeeInstruction(t).equalsIgnoreCase(target)) {
            return node;
        }
        for (Map<String, Object> ch : children(node)) {
            Map<String, Object> f = dfsFindNodeWithExactStripTitle(ch, target);
            if (f != null) return f;
        }
        return null;
    }

    private List<Map<String, Object>> loadRoots() throws IOException {
        List<Map<String, Object>> c = rootsCache;
        if (c != null) return c;
        synchronized (this) {
            if (rootsCache != null) return rootsCache;
            Path json = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath()
                    .resolve("resources").resolve("icdalpha_2026.json");
            if (!Files.exists(json)) return List.of();

            try (FileInputStream fis = new FileInputStream(json.toFile())) {
                JsonFactory jf = new JsonFactory();
                JsonParser jp = jf.createParser(fis);
                JsonToken t = jp.nextToken();
                if (t != JsonToken.START_ARRAY) {
                    rootsCache = List.of();
                    return rootsCache;
                }
                List<Map<String, Object>> list = new ArrayList<>();
                while (jp.nextToken() != JsonToken.END_ARRAY) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> node = mapper.readValue(jp, Map.class);
                    list.add(node);
                }
                rootsCache = list;
                return rootsCache;
            }
        }
    }

    private static String extractSubstanceBeforeUse(String clinicalLower) {
        Matcher m = SUBSTANCE_BEFORE_USE.matcher(clinicalLower);
        if (!m.find()) return null;
        return m.group(1).toLowerCase(Locale.ROOT);
    }

    /**
     * Prefer {@code X use} pattern; else amphetamine/methamphetamine token (for shortcut when no use/with words).
     */
    private static String extractSubstanceHint(String clinicalLower) {
        String s = extractSubstanceBeforeUse(clinicalLower);
        if (s != null) return s;
        Matcher m = AMPHETAMINE_FAMILY.matcher(clinicalLower);
        if (!m.find()) return null;
        return m.group(1).toLowerCase(Locale.ROOT);
    }

    /** True when the query contains the word {@code use} or {@code with} (English words). */
    private static boolean queryIndicatesUseWithPath(String clinicalLower) {
        return WORD_USE.matcher(clinicalLower).find() || WORD_WITH.matcher(clinicalLower).find();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findSubstanceRowUnder(Map<String, Object> ofNode, String substanceHint) {
        Object ch = ofNode.get("children");
        if (!(ch instanceof List<?> list)) return null;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> row = (Map<String, Object>) o;
            String title = str(row.get("title"));
            if (title == null) continue;
            if (indexSubstanceMatchesHint(title, substanceHint)) return row;
        }
        return null;
    }

    /** Match index line (e.g. amphetamines) to the word before "use" in the sentence (amphetamine). */
    private static boolean indexSubstanceMatchesHint(String titleRaw, String substanceHint) {
        String primary = primarySubstanceToken(titleRaw);
        if (primary == null || primary.length() < 3) return false;
        if (primary.equals(substanceHint)) return true;
        if (primary.length() > 3 && primary.endsWith("s") && primary.substring(0, primary.length() - 1).equals(substanceHint)) {
            return true;
        }
        if (substanceHint.length() > 3 && substanceHint.endsWith("s")
                && substanceHint.substring(0, substanceHint.length() - 1).equals(primary)) {
            return true;
        }
        return substanceHint.startsWith(primary) || primary.startsWith(substanceHint);
    }

    /** First word of index line, lowercased, singularized crudely for "amphetamines". */
    private static String primarySubstanceToken(String titleRaw) {
        String t = stripSeeInstruction(titleRaw).trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        int sp = t.indexOf(' ');
        String first = sp > 0 ? t.substring(0, sp) : t;
        first = first.replaceAll("[^a-z0-9]", "");
        if (first.endsWith("s") && first.length() > 4) {
            first = first.substring(0, first.length() - 1);
        }
        return first;
    }

    /**
     * Follow {@code see} with {@code ~} segments from top-level (e.g. Use → (of), then stimulant NEC).
     *
     * @param useOfContext when the See starts with "Use", must be the same "(of)" block as the substance
     *                     row (there are many "(of)" top-level entries in the index).
     */
    private Map<String, Object> resolveSeeChain(List<Map<String, Object>> roots, String seeField,
                                                Map<String, Object> useOfContext) {
        String[] raw = seeField.split("~");
        List<String> parts = new ArrayList<>();
        for (String p : raw) {
            String t = p.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        if (parts.isEmpty()) return null;

        String firstRaw = parts.get(0);
        Map<String, Object> cur;
        if (useOfContext != null && "use".equalsIgnoreCase(firstRaw.trim())) {
            cur = useOfContext;
        } else {
            cur = findAmongRoots(roots, mapSeePart(firstRaw));
        }
        if (cur == null) return null;
        for (int i = 1; i < parts.size(); i++) {
            cur = findChildByTitleToken(cur, parts.get(i).trim());
            if (cur == null) return null;
        }
        return cur;
    }

    private static String mapSeePart(String part) {
        if ("use".equalsIgnoreCase(part)) return "(of)";
        return part;
    }

    private Map<String, Object> findAmongRoots(List<Map<String, Object>> roots, String want) {
        String w = want.toLowerCase(Locale.ROOT);
        for (Map<String, Object> n : roots) {
            String t = str(n.get("title"));
            if (t == null) continue;
            if (titleTokenMatches(t, w)) return n;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findChildByTitleToken(Map<String, Object> parent, String want) {
        String w = want.toLowerCase(Locale.ROOT);
        Object ch = parent.get("children");
        if (!(ch instanceof List<?> list)) return null;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> row = (Map<String, Object>) o;
            String t = str(row.get("title"));
            if (t != null && titleTokenMatches(t, w)) return row;
        }
        return null;
    }

    private static boolean titleTokenMatches(String indexTitle, String wantLower) {
        String stripped = stripSeeInstruction(indexTitle).toLowerCase(Locale.ROOT);
        String compact = stripped.replace(",", " ").replaceAll("\\s+", " ").trim();
        return compact.equals(wantLower) || compact.startsWith(wantLower + " ");
    }

    /**
     * Prefer deepest child whose index title is supported by the clinical text; otherwise parent code.
     */
    private String resolveBestMatchingCode(Map<String, Object> node, String clinicalLower,
                                          List<Map<String, Object>> roots) {
        String best = str(node.get("code"));
        for (Map<String, Object> ch : children(node)) {
            String t = str(ch.get("title"));
            if (t == null) continue;
            if ("with".equalsIgnoreCase(stripSeeInstruction(t).trim())) {
                String fromWith = resolveUnderWith(ch, clinicalLower, roots);
                if (fromWith != null) return fromWith;
            } else if (titleMatchesClinicalSubterm(t, clinicalLower)) {
                String deeper = resolveBestMatchingCode(ch, clinicalLower, roots);
                if (deeper != null) return deeper;
                String own = str(ch.get("code"));
                if (own != null && !own.isBlank()) return own;
                String see = str(ch.get("see"));
                if (see != null && !see.isBlank()) {
                    Map<String, Object> jumped = resolveSeeChain(roots, see, null);
                    if (jumped != null) {
                        String c2 = resolveBestMatchingCode(jumped, clinicalLower, roots);
                        if (c2 != null) return c2;
                        String at = str(jumped.get("code"));
                        if (at != null && !at.isBlank()) return at;
                    }
                }
            }
        }
        return best;
    }

    private String resolveUnderWith(Map<String, Object> withNode, String clinicalLower,
                                   List<Map<String, Object>> roots) {
        String best = null;
        for (Map<String, Object> wch : children(withNode)) {
            String wt = str(wch.get("title"));
            if (wt == null) continue;
            if (!titleMatchesClinicalSubterm(wt, clinicalLower)) continue;
            String sub = resolveBestMatchingCode(wch, clinicalLower, roots);
            if (sub != null) best = sub;
            else {
                String oc = str(wch.get("code"));
                if (oc != null && !oc.isBlank()) best = oc;
            }
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> children(Map<String, Object> node) {
        Object ch = node.get("children");
        if (!(ch instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map) out.add((Map<String, Object>) o);
        }
        return out;
    }

    /**
     * All significant words from the index line (after stripping See) must appear in the clinical text.
     * Skips NEC, etc. Special-case phrases: "in remission", "harmful".
     */
    private static boolean titleMatchesClinicalSubterm(String titleRaw, String clinicalLower) {
        String t = stripSeeInstruction(titleRaw).toLowerCase(Locale.ROOT);
        if (t.contains("in remission")) return clinicalLower.contains("remission");
        if (t.startsWith("harmful")) return clinicalLower.contains("harmful");
        t = t.replace(" nec", "").replace("nec", "").trim();
        String[] words = t.split("[^a-z0-9]+");
        List<String> need = new ArrayList<>();
        for (String w : words) {
            if (w.length() < 3) continue;
            if ("with".equals(w) || "nec".equals(w)) continue;
            need.add(w);
        }
        if (need.isEmpty()) return false;
        for (String w : need) {
            if (!clinicalLower.contains(w)) return false;
        }
        return true;
    }

    private static String stripSeeInstruction(String title) {
        int i = title.toLowerCase(Locale.ROOT).indexOf(" - see");
        return i >= 0 ? title.substring(0, i).trim() : title.trim();
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
