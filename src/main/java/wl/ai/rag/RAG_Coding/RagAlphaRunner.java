package wl.ai.rag.RAG_Coding;

import wl.ai.ragICD.RagAlphaResult;

/**
 * RAG Coding runner for medical ICD extraction.
 *
 * Goal: Extract ICD codes from a sentence. A sentence may contain multiple
 * diagnoses. Flow: 1) Analyze ICD-Guidlines.txt (ICD-10-CM official guidelines)
 * 2) Filter/select from icdalpha_2026 based on guidelines 3) Return all
 * matching ICD codes for the sentence
 *
 * Usage: mvn exec:java "-Dexec.mainClass=wl.ai.rag.RAG_Coding.RagAlphaRunner"
 * "-Dexec.args=insert" mvn exec:java
 * "-Dexec.mainClass=wl.ai.rag.RAG_Coding.RagAlphaRunner" "-Dexec.args=lookup
 * Amphetamine use disorder, severe and Obesity"
 *
 * Storage: By default embeddings go to Qdrant (see {@code QDRANT_URL}, default http://localhost:6333).
 * Set {@code RAG_STORAGE=file} to use legacy NDJSON under resources/RAG_coding/ instead.
 *
 * LLM (chat + embeddings): set {@code LLM_BASE_URL} (OpenAI-compatible base, e.g. {@code http://host/v1}),
 * optional {@code LLM_API_KEY} (Bearer), and {@code LLM_MODEL} (e.g. {@code qwen3-vl-30b}). {@link wl.ai.LLMService}
 * reads these for {@code RagAlphaService}.
 *
 * Files: Input: resources/icdalpha_2026.(txt|json), resources/ICD-Guidlines.txt
 * Output (file mode): resources/RAG_coding/icdalpha_2026.txt, ICD-Guidlines.txt
 *
 * Insert speed: embeddings are batched (64 texts per HTTP call) instead of one
 * call per row. Large icdalpha_2026.json still takes time but is much faster
 * than sequential single embeddings.
 */
public class RagAlphaRunner {

    public static void main(String[] args) {
	String mode = args != null && args.length > 0 ? args[0].trim().toLowerCase() : "lookup";
	RagAlphaService svc = new RagAlphaService();

	try {
	    switch (mode) {
	    case "insert" -> {
		System.out.println("Running insert phase...");
		svc.insert();
		System.out.println("Insert complete. Embeddings written under resources/RAG_coding/");
	    }
	    case "lookup" -> {
		String query =  "Tongue, biopsy";
		if (query.isEmpty()) {
		    System.out.println("Usage: lookup <sentence>");
		    System.out.println("Example: lookup \"Amphetamine use disorder, severe\"");
		    return;
		}
		// More guideline chunks helps Chapter 5 (F10-F19) substance-use rules beat wrong index hits
		RagAlphaResult result = svc.lookupMultiple(query, 20, 12);
		printResult(query, result);
	    }
	    default -> {
		System.out.println("Usage: insert | lookup <sentence>");
		System.out.println("  insert  - Build embeddings from ICD-Guidlines.txt and icdalpha_2026");
		System.out.println("  lookup - Extract ICD codes from sentence (may return multiple codes)");
	    }
	    }
	} catch (Exception e) {
	    System.err.println("Error: " + e.getMessage());
	    e.printStackTrace();
	}
    }

    public static void printResult(String query, RagAlphaResult r) {
	System.out.println("Query: " + query);
	System.out.println();
	if (r.success()) {
	    int i = 1;
	    for (RagAlphaResult.IcdEntry e : r.icdCodes()) {
		System.out.println(i + ". " + e.code() + " - " + (e.description() != null ? e.description() : ""));
		if (e.confidence() != null && !e.confidence().isEmpty()) {
		    System.out.println("   Confidence: " + e.confidence());
		}
		i++;

	    }
	} else {
	    System.out.println("Error: " + (r.errorMessage() != null ? r.errorMessage() : "Unknown"));
	}
    }
}
