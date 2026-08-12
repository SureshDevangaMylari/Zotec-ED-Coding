package com.wl.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

public class ExcelReaderMap {
	public static void main(String[] args) {
		String filePath = RootPath.BASE_DIR + "\\resources\\input\\Share Point Logins.xlsx";
		List<LinkedHashMap<String, String>> excelData = readExcelFile(filePath);

		// Print the data to verify
		for (Map<String, String> row : excelData) {
//            System.out.println(row.get("Check #"));
		}

		for (int i = 0; i < 2; i++) {
			List<String> exRow = new ArrayList<>();
			Map<String, String> row = (Map<String, String>) excelData.get(i);
			System.out.println(row);

			for (Map.Entry<String, String> set : row.entrySet()) {

				// Printing all elements of a Map
				exRow.add(set.getValue());
			}
			System.out.println(exRow);
			exRow.add("status");
//			ExcelCreate.addRow(exRow, "Remmitance");
		}

	}

	public static List<LinkedHashMap<String, String>> readExcelFile(String filePath) {
		List<LinkedHashMap<String, String>> data = new ArrayList<>();
		SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy");
		try (FileInputStream fileInputStream = new FileInputStream(new File(filePath))) {
			Workbook workbook = WorkbookFactory.create(fileInputStream);
			Sheet sheet = workbook.getSheetAt(0); // Get the first sheet

			Iterator<Row> rowIterator = sheet.iterator();
			Row headerRow = rowIterator.next(); // Assuming first row is the header row

			List<String> headers = new ArrayList<>();
			for (Cell cell : headerRow) {
				headers.add(cell.getStringCellValue());
			}

			for (Row row : sheet) {

//                Row row = rowIterator.next();
				Map<String, String> rowData = new LinkedHashMap<>();

				for (int i = 0; i < headers.size(); i++) {

					Cell cell = row.getCell(i);
					String cellValue = "";
					if (cell != null) {
						switch (cell.getCellType()) {
						case STRING:
							cellValue = cell.getStringCellValue();
							break;
						case NUMERIC:
							if (DateUtil.isCellDateFormatted(cell)) {
								Date date = cell.getDateCellValue();
								cellValue = sdf.format(date);
							} else {
								cellValue = String.valueOf((int) cell.getNumericCellValue());
							}
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
				data.add((LinkedHashMap<String, String>) rowData);
			}

			workbook.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return data;
	}
}
