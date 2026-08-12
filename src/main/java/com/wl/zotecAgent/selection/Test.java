package com.wl.zotecAgent.selection;

import java.util.Map;

import com.wl.util.JsonReadService;

public class Test {
    public static void main(String[] args) {
	JsonReadService reader = new JsonReadService();
	Map<String, Object> data = reader.readOutputJson();

	String excelPath = args.length > 0 ? args[0] : "resources/ED_EM Supplemental Tool.xlsx";
	SelectionTestRunner.run(data, excelPath);
    }
}
