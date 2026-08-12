package wl.ai.rag.RAG_Coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant HTTP REST client for embedding storage (default port 6333).
 * Replaces file-based NDJSON for {@link RagAlphaService}.
 */
public final class QdrantRagStorage {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static final String COLLECTION_ICD = "icdalpha_2026";
    public static final String COLLECTION_GUIDELINES = "icd_guidelines";

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public QdrantRagStorage(String baseUrl, ObjectMapper mapper) {
        this.mapper = mapper != null ? mapper : new ObjectMapper();
        String u = baseUrl != null ? baseUrl.trim() : "http://localhost:6333";
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        this.baseUrl = u;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void healthOrThrow() throws IOException {
        Request req = new Request.Builder().url(baseUrl + "/").get().build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("Qdrant not reachable at " + baseUrl + " (HTTP " + r.code() + ")");
            }
        }
    }

    public boolean collectionExists(String name) throws IOException {
        Request req = new Request.Builder().url(baseUrl + "/collections/" + collectionPath(name)).get().build();
        try (Response r = http.newCall(req).execute()) {
            return r.code() == 200;
        }
    }

    public void deleteCollectionIfExists(String name) throws IOException {
        Request req = new Request.Builder().url(baseUrl + "/collections/" + collectionPath(name)).delete().build();
        try (Response r = http.newCall(req).execute()) {
            if (r.code() != 200 && r.code() != 404) {
                String body = r.body() != null ? r.body().string() : "";
                throw new IOException("Qdrant delete collection " + name + ": HTTP " + r.code() + " " + body);
            }
        }
    }

    public void createCollectionCosine(String name, int vectorSize) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode vectors = mapper.createObjectNode();
        vectors.put("size", vectorSize);
        vectors.put("distance", "Cosine");
        root.set("vectors", vectors);

        RequestBody rb = RequestBody.create(mapper.writeValueAsString(root), JSON);
        Request req = new Request.Builder()
                .url(baseUrl + "/collections/" + collectionPath(name))
                .put(rb)
                .build();
        try (Response r = http.newCall(req).execute()) {
            if (r.code() != 200 && r.code() != 409) {
                String body = r.body() != null ? r.body().string() : "";
                throw new IOException("Qdrant create collection " + name + ": HTTP " + r.code() + " " + body);
            }
        }
    }

    /**
     * Upserts points. Payload stores {@code record_id} and nested {@code payload} (same shape as file NDJSON).
     */
    public void upsertPoints(String collection, List<Map<String, Object>> recordsWithVectors, int upsertBatchSize)
            throws IOException {
        if (recordsWithVectors.isEmpty()) return;
        int step = Math.max(1, upsertBatchSize);
        for (int i = 0; i < recordsWithVectors.size(); i += step) {
            int end = Math.min(i + step, recordsWithVectors.size());
            List<Map<String, Object>> slice = recordsWithVectors.subList(i, end);
            ObjectNode body = mapper.createObjectNode();
            ArrayNode points = mapper.createArrayNode();
            for (Map<String, Object> rec : slice) {
                Object id = rec.get("id");
                Object vObj = rec.get("vector");
                @SuppressWarnings("unchecked")
                Map<String, Object> innerPayload = (Map<String, Object>) rec.get("payload");
                if (!(vObj instanceof List<?> rawVec) || rawVec.isEmpty()) continue;

                ArrayNode vec = mapper.createArrayNode();
                for (Object o : rawVec) {
                    if (o instanceof Number n) vec.add(n.doubleValue());
                }
                if (vec.isEmpty()) continue;

                ObjectNode point = mapper.createObjectNode();
                if (id instanceof Number) point.set("id", mapper.valueToTree(id));
                else point.put("id", id != null ? id.toString() : java.util.UUID.randomUUID().toString());
                point.set("vector", vec);

                ObjectNode payload = mapper.createObjectNode();
                if (id != null) payload.put("record_id", id.toString());
                if (innerPayload != null) {
                    payload.set("payload", mapper.valueToTree(innerPayload));
                }
                point.set("payload", payload);
                points.add(point);
            }
            body.set("points", points);

            RequestBody rb = RequestBody.create(mapper.writeValueAsString(body), JSON);
            Request req = new Request.Builder()
                    .url(baseUrl + "/collections/" + collectionPath(collection) + "/points?wait=true")
                    .put(rb)
                    .build();
            try (Response r = http.newCall(req).execute()) {
                if (!r.isSuccessful()) {
                    String err = r.body() != null ? r.body().string() : "";
                    throw new IOException("Qdrant upsert " + collection + ": HTTP " + r.code() + " " + err);
                }
            }
        }
    }

    public record SearchHit(Map<String, Object> record, double score) {}

    /**
     * Cosine vector search with scores (for re-ranking after keyword filter).
     */
    public List<SearchHit> searchWithScores(String collection, List<Double> queryVector, int limit) throws IOException {
        if (queryVector == null || queryVector.isEmpty()) return List.of();
        ObjectNode body = mapper.createObjectNode();
        ArrayNode vec = mapper.createArrayNode();
        for (Double d : queryVector) vec.add(d);
        body.set("vector", vec);
        body.put("limit", limit);
        body.put("with_payload", true);
        body.put("with_vector", false);

        RequestBody rb = RequestBody.create(mapper.writeValueAsString(body), JSON);
        Request req = new Request.Builder()
                .url(baseUrl + "/collections/" + collectionPath(collection) + "/points/search")
                .post(rb)
                .build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) {
                String err = r.body() != null ? r.body().string() : "";
                throw new IOException("Qdrant search " + collection + ": HTTP " + r.code() + " " + err);
            }
            String json = r.body() != null ? r.body().string() : "{}";
            JsonNode root = mapper.readTree(json);
            JsonNode result = root.get("result");
            if (result == null || !result.isArray()) return List.of();

            List<SearchHit> out = new ArrayList<>();
            for (JsonNode hit : result) {
                double score = hit.has("score") ? hit.get("score").asDouble() : 0.0;
                Map<String, Object> rec = new HashMap<>();
                JsonNode payload = hit.get("payload");
                if (payload != null && payload.has("record_id")) {
                    rec.put("id", payload.get("record_id").asText());
                } else {
                    JsonNode idNode = hit.get("id");
                    if (idNode != null && !idNode.isNull()) {
                        rec.put("id", idNode.isTextual() ? idNode.asText() : idNode.toString());
                    }
                }
                if (payload != null && payload.has("payload")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> inner = mapper.convertValue(payload.get("payload"), Map.class);
                    rec.put("payload", inner);
                }
                out.add(new SearchHit(rec, score));
            }
            return out;
        }
    }

    public long collectionPointsCount(String name) throws IOException {
        Request req = new Request.Builder().url(baseUrl + "/collections/" + collectionPath(name)).get().build();
        try (Response r = http.newCall(req).execute()) {
            if (r.code() != 200) return 0;
            String json = r.body() != null ? r.body().string() : "{}";
            JsonNode root = mapper.readTree(json);
            JsonNode result = root.get("result");
            if (result != null && result.has("points_count")) {
                return result.get("points_count").asLong();
            }
            return 0;
        }
    }

    private static String collectionPath(String name) {
        if (name == null || !name.matches("^[a-zA-Z0-9._-]+$")) {
            throw new IllegalArgumentException("Unsafe collection name: " + name);
        }
        return name;
    }

    /** From env {@code QDRANT_URL} or default {@code http://localhost:6333}. */
    public static String resolveBaseUrl() {
        String u = System.getenv("QDRANT_URL");
        if (u != null && !u.isBlank()) return u.trim();
        String prop = System.getProperty("qdrant.url");
        if (prop != null && !prop.isBlank()) return prop.trim();
        return "http://localhost:6333";
    }

    /** When {@code true} (default), use Qdrant; set {@code RAG_STORAGE=file} to use legacy NDJSON files. */
    public static boolean useQdrant() {
        String s = System.getenv("RAG_STORAGE");
        return s == null || !s.equalsIgnoreCase("file");
    }
}
