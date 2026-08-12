package wl.ai.rag.consideration;

import java.util.Map;

/**
 * CLI: resolve a client name against files in {@code considerationFiles} and
 * print JSON payor maps ({@code mca}, {@code medicaid}, {@code BC/BS},
 * {@code HMO's}, {@code Others}) — no Qdrant or embeddings.
 *
 * <p>
 * Optional {@code CONSIDERATION_FILES_DIR}: absolute path to the folder of
 * .docx/.txt/.pdf files.
 * </p>
 *
 * <pre>
 * mvn -q exec:java@consideration-files -Dexec.args=list
 * mvn -q exec:java@consideration-files -Dexec.args=table
 * mvn -q exec:java@consideration-files "-Dexec.args=table Brooks County Hospital"
 * mvn -q exec:java@consideration-files "-Dexec.args=table Some Client rows"
 * </pre>
 */
public final class ConsiderationRagRunner {

    /**
     * Default client when {@code table} is run with no arguments — edit to match
     * {@code list} output.
     */
    private static final String DEFAULT_CLIENT = "Brooks County Hospital";

    private ConsiderationRagRunner() {
    }

    public static void main(String[] args) {
	String[] a = args != null ? args : new String[0];
	String mode = a.length > 0 ? a[0].trim().toLowerCase() : "table";
	ConsiderationRagService svc = new ConsiderationRagService();
	try {
	    switch (mode) {
	    case "list" -> {
		for (String id : svc.listClientIds()) {
		    System.out.println(id);
		}
	    }
	    case "listdetail" -> svc.printClientListDetail(System.out);
	    case "table" -> {
		boolean withRows = false;
		String client;
		client = DEFAULT_CLIENT;
		System.out.println("table: using DEFAULT_CLIENT (edit ConsiderationRagRunner.java)");
		System.out.println();
		Map<String, Object> full = svc.getEntireTableAsSingleMap(client, withRows);
		System.out.println(svc.toTableJson(full));
	    }
	    default -> usage();
	    }
	} catch (Exception e) {
	    System.err.println("Error: " + e.getMessage());
	    e.printStackTrace();
	    System.exit(1);
	}
    }

    private static void usage() {
	System.out.println("Usage:");
	System.out.println("  list       — print effective client_id per file");
	System.out.println("  listdetail — debug: header, file key, row counts");
	System.out.println("  table      — JSON payor maps for DEFAULT_CLIENT (mca, medicaid, BC/BS, HMO's, Others)");
	System.out
		.println("  table <name...>    — client name (multi-word without quotes if last arg is not \"rows\")");
	System.out.println("  table <name...> rows — include row_count + rows array");
    }
}
