package wl.ai.rag.consideration;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Coding Considerations documents: optional {@code Client Name} (or similar) in the Word
 * page header/footer or body, then billing tables. Supports filenames like
 * {@code CTEP - Nevada Coding Considerations - 100824.docx} (see {@link ConsiderationClientKeys#fromFilename(String)}).
 */
public final class ConsiderationDocParser {

    private ConsiderationDocParser() {}

    /**
     * Full parse: header client name (when present) plus all table rows.
     */
    public static ConsiderationParseResult parseDocument(Path path) throws IOException {
        String fileKey = ConsiderationClientKeys.fromFilename(path.getFileName().toString());
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) {
            return parseDocx(path, fileKey);
        }
        if (name.endsWith(".txt")) {	
            return parseTxt(path, fileKey);
        }
        if (name.endsWith(".pdf")) {
            return parsePdf(path, fileKey);
        }
        throw new IOException("Unsupported file type: " + path);
    }

    /** Same as {@link #parseDocument(Path)}{@code .rows()}. */
    public static List<ConsiderationCodingRow> parse(Path path) throws IOException {
        return parseDocument(path).rows();
    }

    private static ConsiderationParseResult parseTxt(Path path, String fileKey) throws IOException {
        return parseTxtLines(Files.readAllLines(path, StandardCharsets.UTF_8), fileKey);
    }

    private static ConsiderationParseResult parseTxtLines(List<String> lines, String fileKey) {
        List<ConsiderationCodingRow> out = new ArrayList<>();
        String section = "Main";
        boolean seenHeader = false;
        String clientHeader = null;

        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;

            String extracted = extractClientNameFromBlock(t);
            if (extracted != null) {
                clientHeader = extracted;
                continue;
            }

            if (t.startsWith("##")) {
                section = t.replaceFirst("^##\\s*", "").trim();
                if (section.isEmpty()) section = "Main";
                seenHeader = false;
                continue;
            }
            String[] parts = splitTsvLine(t);
            if (looksLikeHeaderRow(parts)) {
                seenHeader = true;
                continue;
            }
            if (!seenHeader && parts.length >= 2) {
                seenHeader = true;
            }
            ConsiderationCodingRow row = rowFromParts(section, parts);
            if (row != null && row.billingIssue() != null && !row.billingIssue().isBlank()) {
                out.add(row);
            }
        }
        String supplemental = String.join("\n", lines);
        return new ConsiderationParseResult(clientHeader, fileKey, out, supplemental, null);
    }

    /**
     * Word often puts {@code Client ID}, {@code Client Name : ...}, etc. in one {@link XWPFParagraph}
     * separated by soft line breaks; {@link XWPFParagraph#getText()} then returns multiple lines in one string.
     * Split and run {@link #extractClientNameFromLine(String)} on each physical line.
     */
    static String extractClientNameFromBlock(String block) {
        if (block == null || block.isBlank()) {
            return null;
        }
        String normalized = block.replace('\u00A0', ' ')
                .replace("\u200B", "")
                .replace("\uFEFF", "");
        for (String raw : normalized.split("\\R")) {
            String ex = extractClientNameFromLine(raw);
            if (ex != null) {
                return ex;
            }
        }
        return null;
    }

    /**
     * Lines like {@code Client Name: Acme Hospital}, {@code Client Name : Acme} (space before colon),
     * {@code Client Name\tAcme}, two-column label rows.
     */
    static String extractClientNameFromLine(String line) {
        String t = line.trim();
        if (t.isEmpty()) return null;
        if (t.startsWith("##")) return null;
        String[] parts = splitTsvLine(t);
        if (looksLikeHeaderRow(parts)) return null;

        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("coding consideration")) return null;

        if (lower.startsWith("client name")) {
            // "Client Name : value" — colon may be spaced; strip label and any leading punctuation
            String v = t.replaceFirst("(?i)^client\\s*name\\s*[:\\s\\t.-]*", "").trim();
            v = v.replaceFirst("^[\\s:.-]+", "").trim();
            return v.isEmpty() ? null : v;
        }
        // Bare "Client: ..." — require a colon so we do not treat phrases like "Client callback" as a name
        if (t.matches("(?i)^client\\s*:.+") && !lower.startsWith("client name")) {
            String v = t.replaceFirst("(?i)^client\\s*:\\s*", "").trim();
            return v.length() < 2 ? null : v;
        }
        if (lower.startsWith("facility")) {
            String v = t.replaceFirst("(?i)^facility\\s*(name)?\\s*[:\\s\\t.-]+", "").trim();
            return v.isEmpty() ? null : v;
        }
        if (lower.startsWith("site name")) {
            String v = t.replaceFirst("(?i)^site\\s*name\\s*[:\\s\\t.-]+", "").trim();
            return v.isEmpty() ? null : v;
        }
        if (parts.length >= 2) {
            String c0 = parts[0].trim().toLowerCase(Locale.ROOT);
            if (c0.equals("client name") || c0.equals("client")) {
                String v = parts[1].trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private static String[] splitTsvLine(String line) {
        if (line.contains("\t")) {
            return line.split("\t", -1);
        }
        if (line.contains("|")) {
            return line.split("\\|", -1);
        }
        return new String[] { line };
    }

    private static boolean looksLikeHeaderRow(String[] parts) {
        if (parts.length == 0) return false;
        String c0 = parts[0].trim().toLowerCase(Locale.ROOT);
        return c0.contains("billing") && c0.contains("issue");
    }

    private static ConsiderationCodingRow rowFromParts(String section, String[] p) {
        if (p.length < 2) return null;
        String billing = p[0].trim();
        if (billing.isEmpty()) return null;
        if (looksLikeSectionTitleOnly(billing, p.length)) {
            return null;
        }
        if (looksLikeHeaderRow(p)) {
            return null;
        }
        String medicare = p.length > 1 ? p[1].trim() : "";
        String medicaid = p.length > 2 ? p[2].trim() : "";
        String bcbs = p.length > 3 ? p[3].trim() : "";
        String hmos = p.length > 4 ? p[4].trim() : "";
        String other = p.length > 5 ? p[5].trim() : "";
        String comments = p.length > 6 ? p[6].trim() : "";
        return new ConsiderationCodingRow(section, billing, medicare, medicaid, bcbs, hmos, other, comments);
    }

    private static boolean looksLikeSectionTitleOnly(String billing, int len) {
        if (len > 2) return false;
        String u = billing.toLowerCase(Locale.ROOT);
        return u.contains("physician extender") || u.contains("coding consideration");
    }

    private static ConsiderationParseResult parseDocx(Path path, String fileKey) throws IOException {
        ensureDocxIsOoxmlZip(path);
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(path))) {
            String clientHeader = extractClientFromHeadersAndFooters(doc);
            List<ConsiderationCodingRow> all = new ArrayList<>();
            PayorColumnLabels payorLabels = null;
            StringBuilder bodyParagraphs = new StringBuilder();
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph para) {
                    String t = para.getText();
                    if (t != null && !t.isBlank()) {
                        if (bodyParagraphs.length() > 0) {
                            bodyParagraphs.append('\n');
                        }
                        bodyParagraphs.append(t);
                        String ex = extractClientNameFromBlock(t);
                        if (ex != null) {
                            clientHeader = ex;
                        }
                    }
                } else if (el instanceof XWPFTable table) {
                    String fromLabel = tryExtractClientFromLabelTable(table);
                    if (fromLabel != null) {
                        clientHeader = fromLabel;
                        continue;
                    }
                    ParsedTableChunk chunk = parseDocxTableChunk(table);
                    all.addAll(chunk.rows());
                    if (payorLabels == null && chunk.labels() != null) {
                        payorLabels = chunk.labels();
                    }
                }
            }
            if (clientHeader == null) {
                clientHeader = scanEntireDocxForClientName(doc);
            }
            return new ConsiderationParseResult(clientHeader, fileKey, all, bodyParagraphs.toString(), payorLabels);
        } catch (NotOfficeXmlFileException e) {
            throw new IOException(
                    "Not a valid Office Open XML (.docx) ZIP package: "
                            + path
                            + ". Re-save the file from Word as .docx, or ensure the download is complete. Original error: "
                            + e.getMessage(),
                    e);
        }
    }

    private static byte[] readFilePrefix(Path path, int maxBytes) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[maxBytes];
            int n = in.read(buf);
            if (n <= 0) {
                return new byte[0];
            }
            if (n == maxBytes) {
                return buf;
            }
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        }
    }

    /** .docx is a ZIP archive; real OOXML always starts with a local file header {@code PK\\x03\\x04} (or related PK variants). */
    private static boolean isZipLocalFileHeader(byte[] prefix) {
        if (prefix.length < 4) {
            return false;
        }
        if (prefix[0] != 'P' || prefix[1] != 'K') {
            return false;
        }
        int third = prefix[2] & 0xFF;
        int fourth = prefix[3] & 0xFF;
        return (third == 3 && fourth == 4)
                || (third == 5 && fourth == 6)
                || (third == 7 && fourth == 8)
                || (third == 1 && fourth == 2);
    }

    /** Legacy Word 97–2003 binary document (OLE compound file). */
    private static boolean isOleCompoundDocument(byte[] prefix) {
        return prefix.length >= 8
                && (prefix[0] & 0xFF) == 0xD0
                && (prefix[1] & 0xFF) == 0xCF
                && (prefix[2] & 0xFF) == 0x11
                && (prefix[3] & 0xFF) == 0xE0;
    }

    /**
     * POI throws an obscure {@link NotOfficeXmlFileException} when the path ends in {@code .docx} but the bytes are
     * not a ZIP (e.g. legacy {@code .doc}, HTML, RTF, truncated download). Fail fast with actionable text.
     */
    private static void ensureDocxIsOoxmlZip(Path path) throws IOException {
        byte[] prefix = readFilePrefix(path, 8);
        if (prefix.length == 0) {
            throw new IOException("Empty file (cannot be a valid .docx): " + path);
        }
        if (isOleCompoundDocument(prefix)) {
            throw new IOException(
                    "File is named .docx but content is a legacy Word binary document (.doc OLE). "
                            + "Open in Word and use Save As → Word Document (*.docx), or rename the file to .doc. "
                            + path);
        }
        if (!isZipLocalFileHeader(prefix)) {
            throw new IOException(
                    "File is named .docx but does not start with a ZIP header (not Office Open XML). "
                            + "Common causes: wrong extension, .doc saved as .docx, HTML/RTF export, or corrupted file. "
                            + "Re-save from Word as true .docx or fix the extension. Path: "
                            + path);
        }
    }

    /**
     * Last resort: flatten body (paragraphs + every table cell line) and find a {@code Client Name} line.
     * Catches layouts where the label table was skipped (e.g. formerly {@code rows > 6}) or text sits in odd cells.
     */
    private static String scanEntireDocxForClientName(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder(4096);
        try {
            for (XWPFHeader header : doc.getHeaderList()) {
                appendParagraphTexts(sb, header.getParagraphs());
                for (XWPFTable t : header.getTables()) {
                    appendTableCellTexts(sb, t);
                }
            }
            for (XWPFFooter footer : doc.getFooterList()) {
                appendParagraphTexts(sb, footer.getParagraphs());
                for (XWPFTable t : footer.getTables()) {
                    appendTableCellTexts(sb, t);
                }
            }
        } catch (Exception ignored) {
        }
        for (IBodyElement el : doc.getBodyElements()) {
            if (el instanceof XWPFParagraph para) {
                String t = para.getText();
                if (t != null && !t.isBlank()) {
                    sb.append(t).append('\n');
                }
            } else if (el instanceof XWPFTable table) {
                appendTableCellTexts(sb, table);
            }
        }
        String flat = sb.toString();
        String fromBlock = extractClientNameFromBlock(flat);
        if (fromBlock != null) {
            return fromBlock;
        }
        return extractClientNameByRegex(flat);
    }

    private static final Pattern CLIENT_NAME_SAME_LINE = Pattern.compile(
            "(?is)client\\s*name\\s*[:\\s]+([^\\r\\n]+?)(?:\\r|\\n|$)");
    /** Label cell and value cell on consecutive lines (Word split across cells). */
    private static final Pattern CLIENT_NAME_NEXT_LINE = Pattern.compile(
            "(?is)client\\s*name\\s*[:\\s]*\\R\\s*([^\\r\\n]+)");

    static String extractClientNameByRegex(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = CLIENT_NAME_SAME_LINE.matcher(text);
        if (m.find()) {
            String v = m.group(1).trim().replaceFirst("^[\\s:.-]+", "").trim();
            if (!v.isEmpty()) {
                return v;
            }
        }
        m = CLIENT_NAME_NEXT_LINE.matcher(text);
        if (m.find()) {
            String v = m.group(1).trim();
            if (!v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static void appendParagraphTexts(StringBuilder sb, List<XWPFParagraph> paragraphs) {
        for (XWPFParagraph p : paragraphs) {
            String t = p.getText();
            if (t != null && !t.isBlank()) {
                sb.append(t).append('\n');
            }
        }
    }

    private static void appendTableCellTexts(StringBuilder sb, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                String c = cellText(cell);
                if (!c.isBlank()) {
                    sb.append(c).append('\n');
                }
            }
        }
    }

    /** Word page headers/footers often hold {@code Client Name} (before body tables). */
    private static String extractClientFromHeadersAndFooters(XWPFDocument doc) {
        try {
            for (XWPFHeader header : doc.getHeaderList()) {
                String v = scanHeaderOrFooter(header.getParagraphs(), header.getTables());
                if (v != null) {
                    return v;
                }
            }
            for (XWPFFooter footer : doc.getFooterList()) {
                String v = scanHeaderOrFooter(footer.getParagraphs(), footer.getTables());
                if (v != null) {
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String scanHeaderOrFooter(List<XWPFParagraph> paragraphs, List<XWPFTable> tables) {
        for (XWPFParagraph p : paragraphs) {
            String t = p.getText();
            if (t != null && !t.isBlank()) {
                String ex = extractClientNameFromBlock(t);
                if (ex != null) {
                    return ex;
                }
            }
        }
        for (XWPFTable table : tables) {
            String v = tryExtractClientFromLabelTable(table);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /**
     * Rows like {@code Client Name} | value or {@code Client ID} | CTEP} in a label table (not the billing grid).
     * <p>
     * Do <strong>not</strong> reject tables with many rows: metadata blocks often have 7+ rows (Client ID, Name,
     * EHR, Residents, DLS, Observation, Location, …) and previously skipped those entirely.
     */
    private static String tryExtractClientFromLabelTable(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) return null;
        List<XWPFTableCell> r0cells = rows.get(0).getTableCells();
        if (r0cells.size() >= 2) {
            String r0left = cellText(r0cells.get(0)).trim().toLowerCase(Locale.ROOT);
            if (r0left.contains("billing") && r0left.contains("issue")) {
                return null;
            }
        }
        for (XWPFTableRow row : rows) {
            List<XWPFTableCell> cells = row.getTableCells();
            if (cells.isEmpty()) continue;
            if (cells.size() == 1) {
                String fromCell = extractClientNameFromBlock(cellText(cells.get(0)));
                if (fromCell != null) {
                    return fromCell;
                }
                continue;
            }
            String left = cellText(cells.get(0)).trim().toLowerCase(Locale.ROOT);
            if (left.contains("billing") && left.contains("issue")) continue;
            if (left.contains("medicare") || left.contains("medicaid")) continue;
            if (left.contains("client name") || left.equals("client")) {
                String v = cellText(cells.get(1)).trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        return null;
    }

    private record ParsedTableChunk(List<ConsiderationCodingRow> rows, PayorColumnLabels labels) {}

    private static ParsedTableChunk parseDocxTableChunk(XWPFTable table) {
        List<ConsiderationCodingRow> out = new ArrayList<>();
        PayorColumnLabels headerLabels = null;
        String section = "Main";
        boolean headerDone = false;
        int[] colMap = null;
        for (XWPFTableRow row : table.getRows()) {
            List<XWPFTableCell> cells = row.getTableCells();
            if (cells.isEmpty()) continue;
            String c0 = cellText(cells.get(0)).trim();
            if (c0.isEmpty() && cells.size() == 1) continue;

            if (isSectionHeaderRow(c0)) {
                section = c0;
                headerDone = false;
                out.add(new ConsiderationCodingRow(section, c0, "", "", "", "", "", ""));
                continue;
            }

            List<String> vals = new ArrayList<>();
            for (XWPFTableCell c : cells) {
                vals.add(cellText(c).trim());
            }
            String[] arr = vals.toArray(new String[0]);

            if (!headerDone && looksLikeHeaderRow(arr)) {
                colMap = mapHeaderColumns(arr);
                headerDone = true;
                headerLabels = PayorColumnLabels.fromHeaderRow(arr, colMap);
                continue;
            }
            // Header row like "MCA | Medicaid | BC/BS | HMO's | Others | Comments*" without "Billing Issue"
            // in column 0 — must skip it and use sequential columns (col0 = issue, col1 = MCA/Medicare, ...).
            if (!headerDone && looksLikeBillingPayorHeaderRow(arr)) {
                colMap = defaultColumnMap(arr.length);
                headerDone = true;
                headerLabels = PayorColumnLabels.fromHeaderRow(arr, colMap);
                continue;
            }
            if (!headerDone && arr.length >= 5) {
                colMap = defaultColumnMap(arr.length);
                headerDone = true;
            }
            if (colMap == null) {
                colMap = defaultColumnMap(arr.length);
            }

            ConsiderationCodingRow data = rowFromMapped(section, arr, colMap);
            if (data != null && data.billingIssue() != null && !data.billingIssue().isBlank()) {
                out.add(data);
            }
        }
        return new ParsedTableChunk(out, headerLabels);
    }

    /**
     * True when the first row is payor column titles like {@code MCA | Medicaid | BC/BS | ...} where column 0
     * is labeled MCA but data rows use column 0 for the billing issue name (Word mislabels the first header cell).
     */
    private static boolean looksLikeBillingPayorHeaderRow(String[] arr) {
        if (arr.length < 5 || arr[0] == null || arr[1] == null) {
            return false;
        }
        String h0 = arr[0].trim().toLowerCase(Locale.ROOT);
        String h1 = arr[1].trim().toLowerCase(Locale.ROOT);
        if (h0.contains("billing") && h0.contains("issue")) {
            return false;
        }
        return (h0.equals("mca") || h0.equals("medicare")) && h1.contains("medicaid");
    }

    private static boolean isSectionHeaderRow(String c0) {
        String u = c0.toLowerCase(Locale.ROOT);
        return u.contains("physician extender") || u.equals("physician extenders");
    }

    private static String cellText(XWPFTableCell cell) {
        return cell.getTextRecursively().replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static int[] mapHeaderColumns(String[] headerCells) {
        int n = headerCells.length;
        int[] m = new int[7];
        for (int i = 0; i < 7; i++) m[i] = -1;
        for (int i = 0; i < headerCells.length; i++) {
            String h = headerCells[i].toLowerCase(Locale.ROOT);
            if (h.contains("billing") && h.contains("issue")) m[0] = i;
            else if (h.equals("medicare") || h.equals("mca")
                    || (h.startsWith("medicare") && !h.contains("medicaid"))) m[1] = i;
            else if (h.equals("medicaid")) m[2] = i;
            else if (h.contains("bc") || h.contains("bs")) m[3] = i;
            else if (h.contains("hmo")) m[4] = i;
            else if (h.contains("other") && h.contains("payor")) m[5] = i;
            else if (h.equals("others") || h.equals("other") || h.startsWith("others")) m[5] = i;
            else if (h.contains("comment")) m[6] = i;
        }
        if (m[0] < 0) m[0] = 0;
        if (m[1] < 0 && n > 1) m[1] = 1;
        if (m[2] < 0 && n > 2) m[2] = 2;
        if (m[3] < 0 && n > 3) m[3] = 3;
        if (m[4] < 0 && n > 4) m[4] = 4;
        if (m[5] < 0 && n > 5) m[5] = 5;
        if (m[6] < 0 && n > 6) m[6] = 6;
        // First cell says "MCA" but column 0 is the billing issue; Medicare is column 1 (see looksLikeBillingPayorHeaderRow).
        if (m[0] == m[1] && m[0] >= 0 && headerCells.length >= 2) {
            String h0 = headerCells[0].trim().toLowerCase(Locale.ROOT);
            String h1 = headerCells[1].trim().toLowerCase(Locale.ROOT);
            if ((h0.equals("mca") || h0.equals("medicare")) && h1.contains("medicaid")) {
                return defaultColumnMap(headerCells.length);
            }
        }
        return m;
    }

    private static int[] defaultColumnMap(int n) {
        int[] m = new int[7];
        for (int i = 0; i < 7; i++) m[i] = i < n ? i : -1;
        return m;
    }

    private static ConsiderationCodingRow rowFromMapped(String section, String[] cells, int[] colMap) {
        if (colMap[0] < 0 || colMap[0] >= cells.length) return null;
        String billing = cells[colMap[0]].trim();
        if (billing.isEmpty()) return null;
        if (looksLikeHeaderRow(cells)) return null;
        return new ConsiderationCodingRow(
                section,
                billing,
                cellAt(cells, colMap[1]),
                cellAt(cells, colMap[2]),
                cellAt(cells, colMap[3]),
                cellAt(cells, colMap[4]),
                cellAt(cells, colMap[5]),
                cellAt(cells, colMap[6]));
    }

    private static String cellAt(String[] cells, int colIdx) {
        if (colIdx < 0 || colIdx >= cells.length) return "";
        return cells[colIdx].trim();
    }

    private static ConsiderationParseResult parsePdf(Path path, String fileKey) throws IOException {
        String text;
        try (PDDocument doc = PDDocument.load(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            text = stripper.getText(doc);
        }
        List<String> lines = Arrays.asList(text.split("\\R"));
        return parseTxtLines(lines, fileKey);
    }
}
