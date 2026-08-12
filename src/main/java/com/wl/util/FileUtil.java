package com.wl.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

//langchain sonnet llm
public class FileUtil {
    static Date d = new Date();
    static SimpleDateFormat f = new SimpleDateFormat("MM-dd-yyyy");
    static String date = f.format(d);
    public static final Logger logger = LogManager.getLogger(FileUtil.class);

    public static void main(String[] args) throws IOException {
	String folderPath = RootPath.BASE_DIR + "\\resources\\pdfDownload";
	String filePath = RootPath.BASE_DIR + "\\resources\\output\\output";

//	 
//	String[] headers = { "Lname", "Fname", "Provider NPI", "Provider Name", "Specialty", "Provider Number",
//		"Active/Inactive date of service", "Provider's last revalidation date", "Status" };
	String[] headers = { "fname", "Lname", "id", "Phone:", "Email-1", "Email-2:", "Work Activity Status", "Name",
		"Location", "Certification Level", "Certification Number", "Status", "Original Certification Date",
		"Next Recertification", "Expiration Date", "Contact", "Completed 8-hour supervision training on",
		"daystoExpire", "details", "category", "Sent to Credentialing Email", "Searched Date" };

	FileUtil.create("RBT2-" + date, headers);
	create("RBT123", headers);
	try (FileInputStream fis = new FileInputStream(
		"C:\\Users\\umeshk\\Documents\\UKworkspace\\Applied_ABC_BCBA_RBT_alidation\\resources\\output\\Temp.xlsx");

		Workbook workbook = new XSSFWorkbook(fis)) {

	    // Get the first sheet of the workbook
	    Sheet sheet = workbook.getSheetAt(0);

	    // Get the index of the last row with data
	    int lastRowNum = sheet.getLastRowNum();

	    // Create a new row after the last row
	    Row newRow = sheet.createRow(lastRowNum + 1);
//	    FileUtil.alignColumnsWithDefaultWidth(sheet,25);

	    System.out.println(" done aligningment");
	    try (FileOutputStream fos = new FileOutputStream(
		    "C:\\Users\\umeshk\\Documents\\UKworkspace\\Applied_ABC_BCBA_RBT_alidation\\resources\\output\\Temp.xlsx")) {
		workbook.write(fos);
		System.out.println("Data appended successfully to the last row.");
	    }
	}

    }

    public static void addDataIntoSheet(List<Object> data, String name, String sheetname, int rownum) {

	try (FileInputStream fis = new FileInputStream(name);

		Workbook workbook = new XSSFWorkbook(fis)) {
	    System.out.println(" in the file util");
	    // Get the first sheet of the workbook
//			Sheet sheet = workbook.getSheetAt(1);
	    Sheet sheet = workbook.getSheet(sheetname);
	    // Get the index of the last row with data

	    // Create a new row after the last row
	    Row header = sheet.getRow(0);
	    Cell cell0 = header.createCell(33);
	    Cell cell1 = header.createCell(34);
	    Cell cell2 = header.createCell(35);
	    Cell cell3 = header.createCell(36);
	    Cell cell4 = header.createCell(37);
	    Cell cell5 = header.createCell(38);
	    cell0.setCellValue("notesDate");
	    cell1.setCellValue("description");
	    cell2.setCellValue("currentResponsibility");
	    cell3.setCellValue("balance");
	    cell4.setCellValue("actionCode");
	    cell5.setCellValue("status");
	    Row newRow = sheet.getRow(rownum);

	    // Add data to the new row
	    for (int i = 0; i < data.size(); i++) {
		Cell cell = newRow.createCell(i + 33);
		System.out.println("** " + data.get(i));
		if (data.get(i) == null) {
		    cell.setCellValue("");
		} else {
		    cell.setCellValue(data.get(i).toString());
		}
	    }

	    // Write the changes to the Excel file
	    try (FileOutputStream fos = new FileOutputStream(name)) {
		workbook.write(fos);
		System.out.println("Data appended successfully to the last row.");
	    } catch (Exception e) {
		e.printStackTrace();
		// TODO: handle exception
	    }
	    workbook.close();
	} catch (IOException e) {
	    e.printStackTrace();
	    logger.info(e.getMessage());
	}
    }

    public static List<String> getFilenames(String folderPath, String ext) {
	File folder = new File(folderPath);
	File[] pdfFiles = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(ext));

	if (pdfFiles == null || pdfFiles.length == 0) {
	    return null;
	}
	List<String> pdfFileNames = new ArrayList<>();

	Arrays.sort(pdfFiles, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

	for (File file : pdfFiles) {

	    // Check if the file is a PDF
	    if (file.getAbsolutePath().contains("$")) {
		continue;
	    }
	    pdfFileNames.add(file.getAbsolutePath());
	    System.out.println(file.getAbsolutePath());
	}
	System.out.println(pdfFileNames);
	return pdfFileNames;
    }

    public static List<Map<String, String>> getexceltomap(String path) throws IOException {
	List<Map<String, String>> data = new ArrayList<>();

	try (FileInputStream fileInputStream = new FileInputStream(new File(path))) {
	    Workbook workbook = WorkbookFactory.create(fileInputStream);
	    Sheet sheet = workbook.getSheetAt(0); // Get the first sheet

	    Iterator<Row> rowIterator = sheet.iterator();
	    Row headerRow = rowIterator.next(); // Assuming first row is the header row

	    List<String> headers = new ArrayList<>();
	    for (Cell cell : headerRow) {
		headers.add(cell.getStringCellValue());
	    }

	    for (Row row : sheet) {
		Map<String, String> rowData = new HashMap<>();

		for (int i = 0; i < headers.size(); i++) {

		    Cell cell = row.getCell(i);
		    String cellValue = "";
		    if (cell != null) {
			switch (cell.getCellType()) {
			case STRING:
			    cellValue = cell.getStringCellValue();
			    break;
			case NUMERIC:
			    cellValue = String.valueOf((int) cell.getNumericCellValue());
			    break;
			case BOOLEAN:
			    cellValue = String.valueOf(cell.getBooleanCellValue());
			    break;
			default:
			    cellValue = "";
			}
		    }
		    rowData.put(headers.get(i), cellValue);
		}
		data.add(rowData);
	    }

	    workbook.close();
	} catch (IOException e) {
	    e.printStackTrace();
	}

	return data;
    }

    // creating json file
    /*
     * 
     * @param driver
     * 
     * 
     * 
     * @param Map to print in json file
     * 
     * 
     * 
     * filename for the filename
     * 
     */

 

    public static void PrintJson(Map MainJSOn, String filename)

	    throws JsonProcessingException, IOException, InterruptedException {
	System.out.println("in json print");
	File datafile;

	FileWriter fileWriter2 = null;

	ObjectMapper mapper = new ObjectMapper();

	try {

	    // datafile = new File(path + "\\" + data[0].substring(1, data[0].length()) +

	    // "-" + d + ".json");

	    datafile = new File(RootPath.BASE_DIR + "\\resources\\output\\json\\" + filename + ".json");

	    fileWriter2 = new FileWriter(datafile);

	    fileWriter2.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(MainJSOn).toString());

	    fileWriter2.close();

	} catch (IOException e) {
	    e.printStackTrace();
//		logger.info(e);

	}

    }

    public static void create(String filename, String[] headers) throws IOException {
	String filePath = RootPath.BASE_DIR + "\\resources\\output\\" + filename + ".xlsx";
	System.out.println(" in the excel create");

	System.out.println(date);
	Workbook workbook = new XSSFWorkbook();

	// Create a sheet
	Sheet sheet = workbook.createSheet("sheet1");

	// Create a row
	CellStyle headerStyle = workbook.createCellStyle();

	// Create bold font for headers
	Font headerFont = workbook.createFont();
	headerFont.setBold(true);
	headerStyle.setFont(headerFont);

	headerStyle.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
	headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
	headerStyle.setBorderBottom(BorderStyle.THIN);
	headerStyle.setBorderBottom(BorderStyle.THIN);
	headerStyle.setBorderLeft(BorderStyle.THIN);
	headerStyle.setBorderRight(BorderStyle.THIN);
	headerStyle.setBorderTop(BorderStyle.THIN);
	Row headerRow = sheet.createRow(0);

	// Define headers

	for (int i = 0; i < headers.length; i++) {
	    Cell headerCell = headerRow.createCell(i);
	    headerCell.setCellValue(headers[i]);
	    headerCell.setCellStyle(headerStyle);

	}

	// Align columns with default width
	alignColumnsWithDefaultWidth(sheet);

	// Write the output to a file
	File file = new File(filePath);

	if (file.exists()) {
	    System.out.println("File exists");
	} else {
	    System.out.println("File does not exist");

	    try (FileOutputStream fileOut = new FileOutputStream(filePath)) {
		workbook.write(fileOut);
		System.out.println("Excel File has been created successfully.");
	    } catch (IOException e) {
		e.printStackTrace();
	    } finally {
		try {
		    workbook.close();
		} catch (IOException e) {
		    e.printStackTrace();
		}
	    }
	    workbook.close();
	}
    }

    public static void addData(String[] data, String name) {
	String filePath = RootPath.BASE_DIR + "\\resources\\output\\" + name + ".xlsx";

	try (FileInputStream fis = new FileInputStream(filePath);

		Workbook workbook = new XSSFWorkbook(fis)) {

	    // Get the first sheet of the workbook
	    Sheet sheet = workbook.getSheetAt(0);

	    // Get the index of the last row with data
	    int lastRowNum = sheet.getLastRowNum();

	    // Create a new row after the last row
	    Row newRow = sheet.createRow(lastRowNum + 1);

	    // Add data to the new row
	    for (int i = 0; i < data.length; i++) {
		Cell cell = newRow.createCell(i);
		cell.setCellValue(data[i]);
	    }

	    // Write the changes to the Excel file
	    try (FileOutputStream fos = new FileOutputStream(filePath)) {
		workbook.write(fos);
		System.out.println("Data appended successfully to the last row.");
	    }
	} catch (IOException e) {
	    e.printStackTrace();
	}
    }

    public static void addRow(Map<String, String> obj, String name) {

	List data = new ArrayList(obj.values());
	String filePath = RootPath.BASE_DIR + "\\resources\\output\\" + name + ".xlsx";
	System.out.println(filePath);

	try (FileInputStream fis = new FileInputStream(filePath);

		Workbook workbook = new XSSFWorkbook(fis)) {

	    // Get the first sheet of the workbook
	    Sheet sheet = workbook.getSheetAt(0);

	    // Get the index of the last row with data
	    int lastRowNum = sheet.getLastRowNum();

	    // Create a new row after the last row
	    Row newRow = sheet.createRow(lastRowNum + 1);
	    FileUtil.alignColumnsWithDefaultWidth(sheet, 25);
	    System.out.println(" done aligningment");
	    // Add data to the new row
	    for (int i = 0; i < data.size(); i++) {
		Cell cell = newRow.createCell(i);
		System.out.println("** " + data.get(i));
		if (data.get(i) == null) {
		    cell.setCellValue("");
		} else {
		    cell.setCellValue(data.get(i).toString());
		    sheet.autoSizeColumn(i);
		}
	    }

	    // Write the changes to the Excel file
	    try (FileOutputStream fos = new FileOutputStream(filePath)) {
		workbook.write(fos);
		System.out.println("Data appended successfully to the last row.");
	    }
	} catch (IOException e) {
	    e.printStackTrace();
	    logger.info(e.getMessage());
	}
    }

    public static void create(String name) throws IOException {
	String filePath = RootPath.BASE_DIR + "\\resources\\output\\hpsj\\" + date + "-" + name + ".xlsx";
	System.out.println(" in the excel create");

	System.out.println(date);
	Workbook workbook = new XSSFWorkbook();

	// Create a sheet
	Sheet sheet = workbook.createSheet("sheet1");

	// Create a row
	Row headerRow = sheet.createRow(0);

	// Define headers
	String[] headers = { "facility", "officeKey", "BathNum", "Payment", "Status" };
	for (int i = 0; i < headers.length; i++) {
	    Cell headerCell = headerRow.createCell(i);
	    headerCell.setCellValue(headers[i]);
	}

	// Write the output to a file
	File file = new File(filePath);

	if (file.exists()) {
	    System.out.println("File exists");
	} else {
	    System.out.println("File does not exist");

	    try (FileOutputStream fileOut = new FileOutputStream(filePath)) {
		workbook.write(fileOut);
		System.out.println("Excel File has been created successfully.");
	    } catch (IOException e) {
		e.printStackTrace();
	    } finally {
		try {
		    workbook.close();
		} catch (IOException e) {
		    e.printStackTrace();
		}
	    }
	    workbook.close();
	}
    }

    public static void addRowBysheet(List<Object> data, String name, String sheetnme) {
	String filePath = RootPath.BASE_DIR + "\\resources\\output\\" + name + ".xlsx";
	System.out.println(filePath);

	try (FileInputStream fis = new FileInputStream(filePath);

		Workbook workbook = new XSSFWorkbook(fis)) {

	    // Get the first sheet of the workbook
	    Sheet sheet = workbook.getSheet(sheetnme);

	    // Get the index of the last row with data
	    int lastRowNum = sheet.getLastRowNum();

	    // Create a new row after the last row
	    Row newRow = sheet.createRow(lastRowNum + 1);

	    // Add data to the new row
	    for (int i = 0; i < data.size(); i++) {
		Cell cell = newRow.createCell(i);
		System.out.println("** " + data.get(i));
		if (data.get(i) == null) {
		    cell.setCellValue("");
		} else {
		    cell.setCellValue(data.get(i).toString());
		}
	    }

	    // Write the changes to the Excel file
	    try (FileOutputStream fos = new FileOutputStream(filePath)) {
		workbook.write(fos);
		System.out.println("Data appended successfully to the last row.");
	    }
	} catch (IOException e) {
	    logger.info(e.getMessage());
	}
    }

    /**
     * Aligns all columns in a sheet with a default width.
     * 
     * @param sheet        The sheet to align columns in
     * @param defaultWidth The default width for all columns (in character units,
     *                     default is 15)
     */
    public static void alignColumnsWithDefaultWidth(Sheet sheet, int defaultWidth) {
	if (sheet == null) {
	    logger.warn("Sheet is null, cannot align columns");
	    return;
	}

	int lastColumnNum = 0;
	// Find the last column with data
	for (Row row : sheet) {
	    if (row != null) {
		int lastCellNum = row.getLastCellNum();
		if (lastCellNum > lastColumnNum) {
		    lastColumnNum = lastCellNum;
		}
	    }
	}

	// If no data found, check header row
	if (lastColumnNum == 0 && sheet.getRow(0) != null) {
	    lastColumnNum = sheet.getRow(0).getLastCellNum();
	}

	// Set column width for all columns
	for (int i = 0; i < lastColumnNum; i++) {
	    sheet.setColumnWidth(i, defaultWidth * 256); // POI uses 1/256th of a character width unit
	}

	System.out.println("Aligned " + lastColumnNum + " columns with width " + defaultWidth);
    }

    /**
     * Aligns all columns in a sheet with default width of 15 characters.
     * 
     * @param sheet The sheet to align columns in
     */
    public static void alignColumnsWithDefaultWidth(Sheet sheet) {
	alignColumnsWithDefaultWidth(sheet, 15); // Default width of 15 characters
    }

    /**
     * Aligns all columns in an Excel file with a default width.
     * 
     * @param filePath     The path to the Excel file
     * @param sheetName    The name of the sheet (null for first sheet)
     * @param defaultWidth The default width for all columns (in character units,
     *                     default is 15)
     */
    public static void alignColumnsWithDefaultWidth(String filePath, String sheetName, int defaultWidth) {
	try (FileInputStream fis = new FileInputStream(filePath); Workbook workbook = WorkbookFactory.create(fis)) {

	    Sheet sheet;
	    if (sheetName != null && !sheetName.isEmpty()) {
		sheet = workbook.getSheet(sheetName);
	    } else {
		sheet = workbook.getSheetAt(0);
	    }

	    if (sheet == null) {
		logger.warn("Sheet not found: " + sheetName);
		return;
	    }

	    alignColumnsWithDefaultWidth(sheet, defaultWidth);

	    // Write the changes back to the file
	    try (FileOutputStream fos = new FileOutputStream(filePath)) {
		workbook.write(fos);
		logger.info("Column alignment applied to file: " + filePath);
	    }
	} catch (IOException e) {
	    logger.error("Error aligning columns in file: " + filePath, e);
	    e.printStackTrace();
	}
    }

    /**
     * Aligns all columns in an Excel file with default width of 15 characters.
     * 
     * @param filePath  The path to the Excel file
     * @param sheetName The name of the sheet (null for first sheet)
     */
    public static void alignColumnsWithDefaultWidth(String filePath, String sheetName) {
	alignColumnsWithDefaultWidth(filePath, sheetName, 15);
    }
}
