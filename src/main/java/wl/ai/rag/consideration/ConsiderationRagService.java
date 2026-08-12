package wl.ai.rag.consideration;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads coding-consideration tables from {@code .docx} / {@code .txt} / {@code .pdf} on disk.
 * No vector database or embeddings — pass a client name (or substring) and get JSON with per-payor
 * {@code yes}/{@code no} maps ({@code mca}, {@code medicaid}, {@code BC/BS}, {@code HMO's}, {@code Others}).
 */
public class ConsiderationRagService {

    private static final String RESOURCE_FOLDER = "considerationFiles";

    private final ObjectMapper mapper = new ObjectMapper();

    public ConsiderationRagService() {}

    /**
     * Resolves the consideration folder. Tries, in order:
     * <ol>
     *   <li>{@code CONSIDERATION_FILES_DIR} env (absolute path)</li>
     *   <li>{@code &lt;cwd&gt;/resources/considerationFiles}</li>
     *   <li>{@code &lt;cwd&gt;/src/main/resources/considerationFiles}</li>
     *   <li>{@code &lt;cwd&gt;/considerationFiles}</li>
     *   <li>Classpath {@code /considerationFiles} if it resolves to a directory on disk</li>
     * </ol>
     */
    public Path resolveConsiderationDir() throws IOException {
        String override = System.getenv("CONSIDERATION_FILES_DIR");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override.trim()).normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
            throw new IOException("CONSIDERATION_FILES_DIR is not a directory: " + p);
        }

        Path root = Path.of(System.getProperty("user.dir", ".")).normalize();
        Path[] candidates = new Path[] {
                root.resolve("resources").resolve(RESOURCE_FOLDER),
                root.resolve("src").resolve("main").resolve("resources").resolve(RESOURCE_FOLDER),
                root.resolve(RESOURCE_FOLDER),
        };
        for (Path cwd : candidates) {
            if (Files.isDirectory(cwd)) {
                return cwd.normalize();
            }
        }
        try {
            java.net.URL url = ConsiderationRagService.class.getResource("/" + RESOURCE_FOLDER);
            if (url != null && "file".equals(url.getProtocol())) {
                Path p = Path.of(url.toURI());
                if (Files.isDirectory(p)) {
                    return p;
                }
            }
        } catch (Exception ignored) {
        }
        throw new IOException("Could not locate " + RESOURCE_FOLDER + ". Tried:\n"
                + "  " + candidates[0] + "\n"
                + "  " + candidates[1] + "\n"
                + "  " + candidates[2] + "\n"
                + "Or set CONSIDERATION_FILES_DIR to your folder path.");
    }

    /**
     * Every {@code .docx}, {@code .txt}, {@code .pdf} under {@code dir} recursively.
     * Skips Word lock files ({@code ~$*.docx}) and dotfiles.
     */
    public List<Path> listConsiderationFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        String n = name.toLowerCase(Locale.ROOT);
                        if (name.startsWith("~$")) {
                            return false;
                        }
                        return (n.endsWith(".docx") || n.endsWith(".txt") || n.endsWith(".pdf"))
                                && !n.startsWith(".");
                    })
                    .sorted()
                    .toList();
        }
    }

    /** Distinct {@code client_id} per file (from document header or filename key). */
    public List<String> listClientIds() throws IOException {
        Path dir = resolveConsiderationDir();
        List<String> ids = new ArrayList<>();
        for (Path p : listConsiderationFiles(dir)) {
            ids.add(ConsiderationDocParser.parseDocument(p).effectiveClientId());
        }
        return ids;
    }

    /** Debug: filename, header name, file key, effective id, row count. */
    public void printClientListDetail(PrintStream out) throws IOException {
        Path dir = resolveConsiderationDir();
        for (Path path : listConsiderationFiles(dir)) {
            ConsiderationParseResult doc = ConsiderationDocParser.parseDocument(path);
            out.println("file: " + path.getFileName());
            out.println("  effective_client_id: " + doc.effectiveClientId());
            out.println("  client_name_header:  "
                    + (doc.clientNameFromHeader() != null ? doc.clientNameFromHeader() : "(not parsed)"));
            out.println("  client_key_filename: " + doc.clientKeyFromFilename());
            out.println("  row_count:           " + doc.rows().size());
            out.println();
        }
    }

    /**
     * All matching files as {@code tables[]} with rows + payor maps (read from disk only).
     */
    public Map<String, Object> getEntireTableForClient(String clientIdOrName) throws IOException {
        Path dir = resolveConsiderationDir();
        List<Map<String, Object>> tables = new ArrayList<>();
        for (Path path : listConsiderationFiles(dir)) {
            ConsiderationParseResult doc = ConsiderationDocParser.parseDocument(path);
            if (!doc.matchesClientQuery(clientIdOrName, path.getFileName().toString())) {
                continue;
            }
            List<Map<String, Object>> rowMaps = new ArrayList<>();
            for (ConsiderationCodingRow r : doc.rows()) {
                rowMaps.add(rowToMap(r));
            }
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("client_id", doc.effectiveClientId());
            table.put("client_key_from_filename", doc.clientKeyFromFilename());
            if (doc.clientNameFromHeader() != null) {
                table.put("client_name_header", doc.clientNameFromHeader());
            }
            table.put("filename", path.getFileName().toString());
            table.put("source_path", dir.relativize(path).toString().replace('\\', '/'));
            table.put("row_count", rowMaps.size());
            putPayorYesNoSummaryMaps(table, doc.rows(), doc.payorColumnLabels());
            table.put("rows", rowMaps);
            tables.add(table);
        }
        if (tables.isEmpty()) {
            throw new IOException("No consideration file matched client: " + clientIdOrName
                    + ". Run list / listdetail; use words that appear in the file body or name.");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("table_count", tables.size());
        root.put("tables", tables);
        return root;
    }

    /**
     * First matching file: client metadata plus per-payor maps (JSON keys match the Word table headers when present,
     * e.g. {@code Medicare} or {@code MCA}; otherwise legacy keys {@code mca}, {@code medicaid}, …). No {@code rows} unless
     * {@link #getEntireTableAsSingleMap(String, boolean)} with {@code includeRows == true}.
     */
    public Map<String, Object> getEntireTableAsSingleMap(String clientIdOrName) throws IOException {
        return getEntireTableAsSingleMap(clientIdOrName, false);
    }

    public Map<String, Object> getEntireTableAsSingleMap(String clientIdOrName, boolean includeRows) throws IOException {
        Path dir = resolveConsiderationDir();
        for (Path path : listConsiderationFiles(dir)) {
            ConsiderationParseResult doc = ConsiderationDocParser.parseDocument(path);
            if (!doc.matchesClientQuery(clientIdOrName, path.getFileName().toString())) {
                continue;
            }
            return buildSingleFileTableMap(dir, path, doc, includeRows);
        }
        throw new IOException("No consideration file matched client: " + clientIdOrName
                + ". Run list / listdetail to see ids and filenames; use distinctive words that appear in the document.");
    }

    private Map<String, Object> buildSingleFileTableMap(Path dir, Path path, ConsiderationParseResult doc, boolean includeRows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client_id", doc.effectiveClientId());
        m.put("client_key_from_filename", doc.clientKeyFromFilename());
        if (doc.clientNameFromHeader() != null) {
            m.put("client_name_header", doc.clientNameFromHeader());
        }
        m.put("filename", path.getFileName().toString());
        m.put("source_path", dir.relativize(path).toString().replace('\\', '/'));
        putPayorYesNoSummaryMaps(m, doc.rows(), doc.payorColumnLabels());
        if (includeRows) {
            List<Map<String, Object>> rowMaps = new ArrayList<>();
            for (ConsiderationCodingRow r : doc.rows()) {
                rowMaps.add(rowToMap(r));
            }
            m.put("row_count", rowMaps.size());
            m.put("rows", rowMaps);
        }
        return m;
    }

    private static void putPayorYesNoSummaryMaps(
            Map<String, Object> out, List<ConsiderationCodingRow> rows, PayorColumnLabels labelsFromDoc) {
        PayorColumnLabels keys = labelsFromDoc == null ? PayorColumnLabels.legacyJsonKeys() : labelsFromDoc.withFallbacks();
        out.put(keys.medicare(), buildPayorYesNoMap(rows, ConsiderationCodingRow::medicare));
        out.put(keys.medicaid(), buildPayorYesNoMap(rows, ConsiderationCodingRow::medicaid));
        out.put(keys.bcBs(), buildPayorYesNoMap(rows, ConsiderationCodingRow::bcbs));
        out.put(keys.hmos(), buildPayorYesNoMap(rows, ConsiderationCodingRow::hmos));
        out.put(keys.others(), buildPayorYesNoMap(rows, ConsiderationCodingRow::otherPayors));
    }

    private static Map<String, String> buildPayorYesNoMap(
            List<ConsiderationCodingRow> rows, Function<ConsiderationCodingRow, String> cell) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ConsiderationCodingRow r : rows) {
            if (!isLikelyBillingGridRow(r)) {
                continue;
            }
            String key = r.billingIssue().trim();
            String v = normalizePayorCellToYesNo(cell.apply(r));
            if (v.isEmpty()) {
                continue;
            }
            map.put(key, v);
        }
        return map;
    }

    private static String normalizePayorCellToYesNo(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("yes")) {
            return "yes";
        }
        if (t.startsWith("no")) {
            return "no";
        }
        return "";
    }

    private static boolean isLikelyBillingGridRow(ConsiderationCodingRow r) {
        String b = r.billingIssue();
        if (b == null || b.isBlank()) {
            return false;
        }
        String bTrim = b.trim();
        if (bTrim.length() <= 2 && ("y".equalsIgnoreCase(bTrim) || "n".equalsIgnoreCase(bTrim))) {
            return false;
        }
        String lower = b.toLowerCase(Locale.ROOT);
        if (lower.contains("nevada medicare") && lower.contains("noridian")) {
            return false;
        }
        if (lower.contains("coding rfi") || lower.contains("rfi appropriate")) {
            return false;
        }
        if (bTrim.startsWith("http")) {
            return false;
        }
        if ("medicaid".equalsIgnoreCase(bTrim) || "bc/bs".equalsIgnoreCase(bTrim)) {
            if (rowAllStandardPayorCellsEmpty(r)) {
                return false;
            }
        }
        String med = r.medicare();
        if (med != null && med.length() > 120) {
            return false;
        }
        return true;
    }

    private static boolean rowAllStandardPayorCellsEmpty(ConsiderationCodingRow r) {
        return isCellEmpty(r.medicare()) && isCellEmpty(r.medicaid()) && isCellEmpty(r.bcbs())
                && isCellEmpty(r.hmos()) && isCellEmpty(r.otherPayors());
    }

    private static boolean isCellEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    public String toTableJson(Map<String, Object> tableRoot) throws JsonProcessingException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tableRoot);
    }

    public Optional<ConsiderationParseResult> findFirstMatchingParseResult(String clientIdOrName) throws IOException {
        if (clientIdOrName == null || clientIdOrName.isBlank()) {
            return Optional.empty();
        }
        Path dir = resolveConsiderationDir();
        for (Path path : listConsiderationFiles(dir)) {
            ConsiderationParseResult doc = ConsiderationDocParser.parseDocument(path);
            if (doc.matchesClientQuery(clientIdOrName, path.getFileName().toString())) {
                return Optional.of(doc);
            }
        }
        return Optional.empty();
    }

    public static String formatBillingTableTsv(ConsiderationParseResult doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("Billing Issue:\tMCA\tMedicaid\tBC/BS\tHMO's\tOthers\tComments*\n");
        for (ConsiderationCodingRow r : doc.rows()) {
            sb.append(escapeTsvCell(r.billingIssue())).append('\t')
                    .append(escapeTsvCell(r.medicare())).append('\t')
                    .append(escapeTsvCell(r.medicaid())).append('\t')
                    .append(escapeTsvCell(r.bcbs())).append('\t')
                    .append(escapeTsvCell(r.hmos())).append('\t')
                    .append(escapeTsvCell(r.otherPayors())).append('\t')
                    .append(escapeTsvCell(r.comments()))
                    .append('\n');
        }
        sb.append("\nClient Specific Instructions:\n");
        return sb.toString();
    }

    private static String escapeTsvCell(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static Map<String, Object> rowToMap(ConsiderationCodingRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("section", r.section());
        m.put("billing_issue", r.billingIssue());
        m.put("medicare", r.medicare());
        m.put("medicaid", r.medicaid());
        m.put("bc_bs", r.bcbs());
        m.put("hmos", r.hmos());
        m.put("other_payors", r.otherPayors());
        m.put("comments", r.comments());
        return m;
    }
}
