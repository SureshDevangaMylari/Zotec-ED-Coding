package com.wl.zotecAgent;

import org.apache.poi.ss.usermodel.*;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

/**
 * Simple utility to load ED_EM Supplemental Tool.xlsx and print the first 20 rows
 * of each sheet (COPA, DATA, RISK) with column headers for structure analysis.
 * <p>
 * Run: From excel-structure-reader subfolder: {@code mvn compile exec:java}
 * Or: {@code java -cp ... com.wl.zotecAgent.ED_EMSupplementalStructurePrinter}
 */
public class ED_EMSupplementalStructurePrinter {

    private static final String EXCEL_PATH = "resources/ED_EM Supplemental Tool.xlsx";
    private static final String[] TARGET_SHEETS = { "COPA", "DATA", "RISK" };
    private static final int MAX_ROWS = 20;
    /** Row index for actual column headers (row 0 = title, row 1 = subtitle, row 2 = headers). */
    private static final int HEADER_ROW = 2;

    public static void main(String[] args) {
        Path resolved = Path.of(EXCEL_PATH);
        if (!resolved.toFile().exists()) {
            resolved = Path.of(System.getProperty("user.dir"), EXCEL_PATH);
        }

        try (InputStream is = new FileInputStream(resolved.toFile());
             Workbook wb = WorkbookFactory.create(is)) {

            System.out.println("=== ED_EM Supplemental Tool - Structure Analysis ===\n");

            for (String targetName : TARGET_SHEETS) {
                Sheet sheet = findSheet(wb, targetName);
                if (sheet == null) {
                    System.out.println("--- Sheet '" + targetName + "' NOT FOUND ---\n");
                    continue;
                }
                printSheet(sheet, targetName);
            }

        } catch (Exception e) {
            System.err.println("Error loading Excel: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Sheet findSheet(Workbook wb, String targetName) {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet s = wb.getSheetAt(i);
            if (s.getSheetName().equalsIgnoreCase(targetName)) {
                return s;
            }
        }
        return null;
    }

    private static void printSheet(Sheet sheet, String sheetLabel) {
        System.out.println("========== Sheet: " + sheet.getSheetName() + " (" + sheetLabel + ") ==========");

        Row headerRow = sheet.getRow(0);
        Row effectiveHeaderRow = sheet.getRow(HEADER_ROW);
        if (headerRow == null) {
            System.out.println("No header row.");
            System.out.println();
            return;
        }

        // Use row 2 as effective headers (actual column names for matching)
        Row rowToUse = (effectiveHeaderRow != null) ? effectiveHeaderRow : headerRow;
        List<String> headers = new ArrayList<>();
        int lastCol = effectiveHeaderRow != null
                ? Math.max(headerRow.getLastCellNum(), effectiveHeaderRow.getLastCellNum())
                : headerRow.getLastCellNum();
        for (int c = 0; c < lastCol; c++) {
            headers.add(getCellString(rowToUse.getCell(c)));
        }

        System.out.println("\n--- Column headers (exact, row " + (effectiveHeaderRow != null ? HEADER_ROW : 0) + ") ---");
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            if (h == null || h.isBlank()) h = "[empty_" + i + "]";
            System.out.println("  [" + i + "] \"" + h + "\"");
        }

        int dataStartRow = (effectiveHeaderRow != null) ? HEADER_ROW + 1 : 1;
        System.out.println("\n--- First " + MAX_ROWS + " data rows ---");
        int rowCount = 0;
        for (int r = dataStartRow; r <= sheet.getLastRowNum() && rowCount < MAX_ROWS; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            Map<String, String> rowMap = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String h = headers.get(c);
                if (h == null || h.isBlank()) h = "col_" + c;
                rowMap.put(h, getCellString(row.getCell(c)));
            }

            boolean hasData = rowMap.values().stream().anyMatch(v -> v != null && !v.isBlank());
            if (hasData) {
                System.out.println("\n  Row " + (rowCount + 1) + ":");
                for (Map.Entry<String, String> e : rowMap.entrySet()) {
                    String v = e.getValue();
                    if (v == null) v = "";
                    System.out.println("    \"" + e.getKey() + "\" => \"" + v + "\"");
                }
                rowCount++;
            }
        }
        System.out.println();
    }

    private static String getCellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield (d == (long) d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }
}
