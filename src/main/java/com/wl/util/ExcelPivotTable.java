package com.wl.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

public class ExcelPivotTable {

    public static void main(String[] args) {
	String inputFile = "C:\\Users\\umeshk\\Documents\\UKworkspace\\Applied_ABC_BCBA_RBT_alidation\\resources\\output\\RBT Enrollment Status summary-11-27-2025.xlsx";
	String outputFile = "output_pivot.xlsx";

	try {
	    pivotExcelData(inputFile, outputFile, 7); // Column 14 (0-indexed = 13)
	    System.out.println("Pivot table created successfully!");
	} catch (IOException e) {
	    e.printStackTrace();
	}
    }

    public static void pivotExcelData(String inputPath, String outputPath, int pivotColumnIndex) throws IOException {

	FileInputStream fis = new FileInputStream(inputPath);
	Workbook workbook = new XSSFWorkbook(fis);
	Sheet sheet = workbook.getSheetAt(0);

	CellStyle headerStyle = workbook.createCellStyle();
	headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
	headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
	headerStyle.setBorderBottom(BorderStyle.THIN);
	headerStyle.setBorderBottom(BorderStyle.THIN);
	headerStyle.setBorderLeft(BorderStyle.THIN);
	headerStyle.setBorderRight(BorderStyle.THIN);
	headerStyle.setBorderTop(BorderStyle.THIN);
	// BLUE data row style
	CellStyle dataStyle = workbook.createCellStyle();
//	dataStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
//	dataStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
	dataStyle.setBorderBottom(BorderStyle.THIN);
	dataStyle.setBorderLeft(BorderStyle.THIN);
	dataStyle.setBorderRight(BorderStyle.THIN);
	dataStyle.setBorderTop(BorderStyle.THIN);
	// GREEN total row style
	CellStyle totalStyle = workbook.createCellStyle();
	totalStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
	totalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
	totalStyle.setBorderBottom(BorderStyle.THIN);

	// Read header for pivot column
	Row headerRow = sheet.getRow(0);
	Cell headerCell = headerRow.getCell(pivotColumnIndex);
	String columnHeader = headerCell != null ? headerCell.toString() : "Column " + (pivotColumnIndex + 1);

	// Count occurrences of each unique value in the pivot column
	Map<String, Integer> pivotCount = new LinkedHashMap<>();
	int total = 0;
	for (int i = 1; i <= sheet.getLastRowNum(); i++) {
	    Row row = sheet.getRow(i);
	    if (row == null)
		continue;

	    Cell pivotCell = row.getCell(pivotColumnIndex);
	    String pivotValue = pivotCell != null ? pivotCell.toString() : "N/A";

	    pivotCount.put(pivotValue, pivotCount.getOrDefault(pivotValue, 0) + 1);
	    total++;
	}

	fis.close();
//        workbook.close();

	// Create output workbook with pivot summary
	Workbook outputWorkbook = workbook;
	Sheet outputSheet = null;
	try {
	    outputSheet = outputWorkbook.createSheet("Pivot Summary");
	} catch (Exception e) {
	    outputSheet = outputWorkbook.getSheet("Pivot Summary");
	    // TODO: handle exception
	}

	// Create header row
	Row outputHeaderRow = outputSheet.createRow(9);
	Cell header = outputHeaderRow.createCell(10);
	Cell headerval = outputHeaderRow.createCell(11);
	header.setCellValue(columnHeader);
	headerval.setCellValue("Count");
	header.setCellStyle(headerStyle);
	headerval.setCellStyle(headerStyle);

	// Write pivot data
	int rowNum = 10;
	for (Map.Entry<String, Integer> entry : pivotCount.entrySet()) {
	   
	    Row outputRow = outputSheet.createRow(rowNum++);
	    Cell cell1 = outputRow.createCell(10);
	    Cell cell2 = outputRow.createCell(11);
	    cell1.setCellValue(entry.getKey());
	    cell1.setCellStyle(dataStyle);
	    cell2.setCellValue(entry.getValue());
	    cell2.setCellStyle(dataStyle);

	}
	  for (int i = 10; i < 13; i++) {
	            sheet.autoSizeColumn(i);
	        }
	Row outputRow = outputSheet.createRow(rowNum++);
	Cell outcell1 = outputRow.createCell(10);

	outcell1.setCellValue("Total");
	outcell1.setCellStyle(headerStyle);
	Cell outcell2 = outputRow.createCell(11);
	outcell2.setCellStyle(headerStyle);
	outcell2.setCellValue(total);

	// Auto-size columns
	outputSheet.autoSizeColumn(15);
	outputSheet.autoSizeColumn(15);

	// Write to file
	FileOutputStream fos = new FileOutputStream(inputPath);
	outputWorkbook.write(fos);
	fos.close();
	outputWorkbook.close();
	workbook.close();
    }
}