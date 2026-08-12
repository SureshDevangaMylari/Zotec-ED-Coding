package com.wl.zotecAgent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads all sheets from ED_EM Supplemental Tool.xlsx.
 * Maps: PROBLEM (copa sheet), DATA (data sheet), RISK (risk sheet).
 * Used to determine which radio/checkbox values to select based on encounter data.
 */
public class ED_EMSupplementalExcelReader {

    private static final Logger log = LogManager.getLogger(ED_EMSupplementalExcelReader.class);
    private static final String EXCEL_PATH = "resources/ED_EM Supplemental Tool.xlsx";

    private final Map<String, List<Map<String, String>>> sheetData = new LinkedHashMap<>();

    public ED_EMSupplementalExcelReader() {
        loadSheets();
    }

    public ED_EMSupplementalExcelReader(String path) {
        loadSheets(path);
    }

    private void loadSheets() {
        loadSheets(EXCEL_PATH);
    }

    private void loadSheets(String path) {
        Path resolved = Path.of(path);
        if (!Files.exists(resolved)) {
            resolved = Path.of(System.getProperty("user.dir"), path);
        }
        try (InputStream is = new FileInputStream(resolved.toFile());
             Workbook wb = WorkbookFactory.create(is)) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                String sheetName = sheet.getSheetName();
                List<Map<String, String>> rows = readSheet(sheet);
                sheetData.put(sheetName, rows);
                log.info("Loaded sheet '{}' with {} rows", sheetName, rows.size());
            }
        } catch (IOException e) {
            log.warn("Could not load ED_EM Supplemental Tool: {}", e.getMessage());
        }
    }

    private List<Map<String, String>> readSheet(Sheet sheet) {
        List<Map<String, String>> rows = new ArrayList<>();
        Row headerRow = sheet.getRow(0);
        if (headerRow == null)
            return rows;

        List<String> headers = new ArrayList<>();
        int lastCol = headerRow.getLastCellNum();
        for (int c = 0; c < lastCol; c++) {
            headers.add(getCellString(headerRow.getCell(c)));
        }

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null)
                continue;
            Map<String, String> rowMap = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String h = headers.get(c);
                if (h == null || h.isBlank())
                    h = "col_" + c;
                rowMap.put(h, getCellString(row.getCell(c)));
            }
            if (!rowMap.values().stream().allMatch(v -> v == null || v.isBlank()))
                rows.add(rowMap);
        }
        return rows;
    }

    private static String getCellString(Cell cell) {
        if (cell == null)
            return "";
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

    /** Returns all sheet names. */
    public Set<String> getSheetNames() {
        return new LinkedHashSet<>(sheetData.keySet());
    }

    /** Returns data for a sheet (e.g. "copa", "data", "Risk"). */
    public List<Map<String, String>> getSheetData(String sheetName) {
        return sheetData.getOrDefault(sheetName, List.of());
    }

    /** Returns copa (problem) sheet data. */
    public List<Map<String, String>> getCopaData() {
        for (String name : sheetData.keySet()) {
            if (name.toLowerCase().contains("copa") || name.toLowerCase().contains("problem"))
                return sheetData.get(name);
        }
        return getSheetData("copa");
    }

    /** Returns data sheet. */
    public List<Map<String, String>> getDataSheet() {
        for (String name : sheetData.keySet()) {
            if (name.equalsIgnoreCase("data"))
                return sheetData.get(name);
        }
        return getSheetData("data");
    }

    /** Returns risk sheet. */
    public List<Map<String, String>> getRiskData() {
        for (String name : sheetData.keySet()) {
            if (name.toLowerCase().contains("risk"))
                return sheetData.get(name);
        }
        return getSheetData("Risk");
    }

    /** Debug: print sheet structure. */
    public static void main(String[] args) {
        ED_EMSupplementalExcelReader r = new ED_EMSupplementalExcelReader();
        for (String name : r.getSheetNames()) {
            System.out.println("\n=== Sheet: " + name + " ===");
            List<Map<String, String>> rows = r.getSheetData(name);
            if (!rows.isEmpty()) {
                System.out.println("Headers: " + rows.get(0).keySet());
                for (int i = 0; i < Math.min(5, rows.size()); i++)
                    System.out.println("Row" + i + ": " + rows.get(i));
            }
        }
    }
}
