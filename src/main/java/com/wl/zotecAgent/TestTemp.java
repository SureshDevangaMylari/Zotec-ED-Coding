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

import wl.ai.rag.RAG_Coding.RagAlphaRunner;
import wl.ai.rag.RAG_Coding.RagAlphaService;
import wl.ai.ragICD.RagAlphaResult;
import wl.ai.ragICD.RagAlphaResult.IcdEntry;

public class TestTemp {
    private static final Logger logger = LoggerFactory.getLogger(TestTemp.class);

    public static void main(String[] args) throws IOException {
	// TODO Auto-generated method stub

	logger.info("Application started");
	RagAlphaService svc = new RagAlphaService();
	JsonReadService reader = new JsonReadService();
	Map<String, Object> data = reader.readOutputJson();

	List<Object> diagnosis = (List<Object>) data.get("ed_diagnosis");
	Set<Object> diagnosisSet = new HashSet(diagnosis);
	System.out.println(diagnosis);
	Set<String> ICD_codes = new HashSet<String>();
	Service s = new Service();

	for (Object object : diagnosisSet) {

	    Map<String, String> dig = (Map<String, String>) object;

	    RagAlphaResult result = svc.lookupMultiple(dig.get("diagnosis"), 20, 12);
	    RagAlphaRunner.printResult(dig.get("diagnosis"), result);
	    System.out.println();

	    result.icdCodes().stream().map(entry -> entry.code()).forEach(e -> ICD_codes.add(e));
//	    s.validateICD(ICD_codes,page);
	}
//	System.out.println(ICD_codes);

	System.out.println(ICD_codes);
    }

}
