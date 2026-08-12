package wl.ai.ragICD;

/**
 * Test runner: call findICD and findProcedure from main with text. Usage: java
 * RagTestRunner <text> Example: java RagTestRunner "chest pain"
 */
public class RagTestRunner {

    private static final String MASTER_DATA = "resources/masterData.json";
    private static final String PROCEDURE_DATA = "resources/procedureData.json";

    public static void main(String[] args) {
	String text = "Amphetamini use disorder,servere";
	if (text.isEmpty()) {
	    System.out.println("Usage: RagTestRunner \"your text\"");
	    return;
	}

	RagCodeLookupService service = new RagCodeLookupService(MASTER_DATA, PROCEDURE_DATA);

	System.out.println("Input: " + text + "\n");

	RagLookupResult icdResult = service.findICD(text);
	System.out.println("--- ICD ---");
	printResult(icdResult);

//        RagLookupResult procResult = service.findProcedure(text);
//        System.out.println("--- Procedure ---");
//        printResult(procResult);
    }

    private static void printResult(RagLookupResult r) {
	if (r.success()) {
	    System.out.println("Code: " + r.code());
	    if (r.description() != null && !r.description().isEmpty())
		System.out.println("Description: " + r.description());
	} else {
	    System.out.println("Error: " + (r.errorMessage() != null ? r.errorMessage() : "Unknown"));
	}
	System.out.println();
    }
}
