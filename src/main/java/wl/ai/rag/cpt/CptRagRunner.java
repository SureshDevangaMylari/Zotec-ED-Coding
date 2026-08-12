package wl.ai.rag.cpt;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * CPT RAG — same stack as ICD {@link wl.ai.rag.RAG_Coding.RagAlphaService}: {@link wl.ai.LLMService} + Qdrant HTTP
 * ({@link wl.ai.rag.RAG_Coding.QdrantRagStorage#resolveBaseUrl()}).
 *
 * <p>Data (under project {@code resources/}): {@code cpt-codes.json} or {@code cpt-Codes.json},
 * {@code cpt-guidelines.txt} or {@code cpt-guidlines.txt}, and {@code jsonfolder/output.json} for clinical context.</p>
 *
 * <p>Env: {@code LLM_BASE_URL}, {@code LLM_API_KEY}, {@code LLM_MODEL}, {@code EMBEDDING_MODEL}, {@code EMBEDDING_API_URL},
 * {@code QDRANT_URL} (default {@code http://localhost:6333}).</p>
 *
 * <pre>
 * mvn exec:java -Dexec.mainClass=wl.ai.rag.cpt.CptRagRunner -Dexec.args="insert"
 * mvn exec:java -Dexec.mainClass=wl.ai.rag.cpt.CptRagRunner -Dexec.args="lookup What CPT codes apply to a new patient office visit?"
 * </pre>
 */
public class CptRagRunner {

    public static void main(String[] args) throws Exception {
        String[] a = args == null ? new String[0] : args;
        String mode = a.length > 0 ? a[0].trim().toLowerCase() : "lookup";
        CptRagLangChainService svc = new CptRagLangChainService();
        switch (mode) {
            case "insert" -> {
                System.out.println("Building CPT index in Qdrant (LLMService + HTTP, same as ICD RAG)…");
                svc.insert();
                System.out.println("Done.");
            }
            case "lookup" -> {
                String query;
                Path output = null;
                    query = "Suggest CPT codes for documented procedures and visits in the clinical record.";
                
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
                } else {
                    System.err.println(r.errorMessage());
                }
            }
            default -> System.out.println("Usage: insert | lookup [question] [path-to-output.json]");
        }
    }
}
