package com.wl.zotecAgent;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wl.util.JsonReadService;
import com.wl.zotecAgent.edem.ED_EMExcelReader;
import com.wl.zotecAgent.selection.LLMSelectionService;

import wl.ai.LLMService;
import wl.ai.rag.RAG_Coding.RagAlphaRunner;
import wl.ai.rag.RAG_Coding.RagAlphaService;
import wl.ai.rag.consideration.ConsiderationRagService;
import wl.ai.ragICD.RagAlphaResult;
import wl.ai.ragICD.RagAlphaResult.IcdEntry;

public class TestTemp2 {
    private static final Logger logger = LoggerFactory.getLogger(TestTemp2.class);

    public static void main(String[] args) throws IOException {
	// TODO Auto-generated method stub

	logger.info("Application started");
	ConsiderationRagService svc = new ConsiderationRagService();

//	System.out.println(ICD_codes);
	Map<String, Object> full = svc.getEntireTableAsSingleMap("CTEP - CARSON TAHOE EMERGENCY PHYSICIANS LLP", false);
	System.out.println(svc.toTableJson(full));

	System.out.println(full);

	String DEFAULT_EXCEL_PATH = "resources/ED_EM Supplemental Tool.xlsx";
	JsonReadService reader = new JsonReadService();
	Map<String, Object> data = reader.readOutputJson();
	ED_EMExcelReader excelReader = new ED_EMExcelReader(DEFAULT_EXCEL_PATH);
	LLMSelectionService selectionService = new LLMSelectionService(new LLMService(), excelReader);

//	selections= SelectionTestRunner.run(data, DEFAULT_EXCEL_PATH);

	Map<String, Object> selections = selectionService.decideSelections(data);
	logger.info("{}",selections);
	
	
	
    }

}
