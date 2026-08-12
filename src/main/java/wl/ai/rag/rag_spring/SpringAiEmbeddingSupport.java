package wl.ai.rag.rag_spring;

import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges Spring AI {@link EmbeddingModel} to the {@code List<Double>} vectors used by
 * {@link wl.ai.rag.RAG_Coding.QdrantRagStorage} (same shape as {@link wl.ai.LLMService} embeddings).
 */
public final class SpringAiEmbeddingSupport {

    private SpringAiEmbeddingSupport() {}

    public static List<Double> embedToDoubles(EmbeddingModel model, String text) {
        EmbeddingResponse response = model.call(new EmbeddingRequest(List.of(text), null));
        Embedding e = response.getResult();
        return floatArrayToDoubles(e.getOutput());
    }

    public static List<List<Double>> embedBatchToDoubles(EmbeddingModel model, List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        EmbeddingResponse response = model.call(new EmbeddingRequest(texts, null));
        List<List<Double>> out = new ArrayList<>();
        for (Embedding e : response.getResults()) {
            out.add(floatArrayToDoubles(e.getOutput()));
        }
        return out;
    }

    private static List<Double> floatArrayToDoubles(float[] f) {
        if (f == null) return List.of();
        List<Double> l = new ArrayList<>(f.length);
        for (float v : f) {
            l.add((double) v);
        }
        return l;
    }
}
