package com.wl.zotecAgent.edem;

import org.apache.poi.ss.usermodel.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads all sheets from ED_EM Supplemental Tool.xlsx.
 * Structure: Row 0-1 = titles, Row 2 = headers, Row 3+ = data.
 * Sheets: COPA (Problem), DATA, RISK.
 */
public class ED_EMExcelReader {

    private static final Logger log = LogManager.getLogger(ED_EMExcelReader.class);
    private static final String EXCEL_PATH = "resources/ED_EM Supplemental Tool.xlsx";
    private static final int HEADER_ROW = 2;
    private static final int DATA_START_ROW = 3;

    private final Map<String, List<Map<String, String>>> sheetData = new LinkedHashMap<>();

    public ED_EMExcelReader() {
        loadSheets();
    }

    public ED_EMExcelReader(String path) {
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
        Row headerRow = sheet.getRow(HEADER_ROW);
        if (headerRow == null)
            return rows;

        List<String> headers = new ArrayList<>();
        int lastCol = headerRow.getLastCellNum();
        for (int c = 0; c < lastCol; c++) {
            headers.add(getCellString(headerRow.getCell(c)));
        }

        for (int r = DATA_START_ROW; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, String> rowMap = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                String h = headers.get(c);
                if (h == null || h.isBlank())
                    h = "col_" + c;
                rowMap.put(h, getCellString(row.getCell(c)));
            }
            if (rowMap.values().stream().anyMatch(v -> v != null && !v.isBlank()))
                rows.add(rowMap);
        }
        return rows;
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

    public Set<String> getSheetNames() {
        return new LinkedHashSet<>(sheetData.keySet());
    }

    public List<Map<String, String>> getCopaData() {
        return sheetData.getOrDefault("COPA", List.of());
    }

    public List<Map<String, String>> getDataSheet() {
        return sheetData.getOrDefault("DATA", List.of());
    }

    public List<Map<String, String>> getRiskData() {
        return sheetData.getOrDefault("RISK", List.of());
    }

    /** Returns data from any sheet by name. */
    public List<Map<String, String>> getSheet(String sheetName) {
        return sheetData.getOrDefault(sheetName, List.of());
    }
}
