package wl.ai.rag.cpt;

import java.io.IOException;
import java.nio.file.Path;

public class Test {

    public static void main(String[] args) throws IOException {
	// TODO Auto-generated method stub

	CptRagLangChainService svc = new CptRagLangChainService();

	Path output = null;
	String query = "Suggest CPT codes for documented procedures and visits in the clinical record.";

	CptRagResult r = svc.lookup(query, output);
	if (r.success()) {
	    int i = 1;
	    for (CptRagResult.CptEntry e : r.cptCodes()) {
		System.out.println(i + ". " + e.code() + " — " + e.description());
		if (e.confidence() != null) {
		    System.out.println("   Confidence: " + e.confidence());
		}
		i++;
	    }
	}
    }

}
