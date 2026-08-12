package wl.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Global LLM service — the ONLY class that talks to the self-hosted model.
 * Every other service (ClinicalExtractionService, WorklistReaderService, etc.)
 * calls this class; they never make HTTP requests themselves.
 *
 * Accepts systemPrompt + userPrompt as parameters for every call.
 * Nothing is hardcoded here — callers decide what prompts to send.
 *
 * Three input modes:
 *   1. Text chat     — call(systemPrompt, userPrompt)
 *   2. Image file    — callWithImage(file, systemPrompt, userPrompt)
 *   3. Base64 image  — callWithBase64(base64, mediaType, systemPrompt, userPrompt)
 *
 * Two return modes for each:
 *   - String  (raw LLM text)
 *   - Map     (parsed JSON → Map<String,Object>)
 *
 * Usage:
 *   LLMService llm = new LLMService();
 *
 *   // chat
 *   String text   = llm.call("system msg", "user msg");
 *   Map map       = llm.callToMap("system msg", "user msg");
 *
 *   // image file
 *   String text   = llm.callWithImage(file, "system msg", "user msg");
 *   Map map       = llm.callWithImageToMap(file, "system msg", "user msg");
 *
 *   // base64 image
 *   String text   = llm.callWithBase64(b64, "image/png", "system msg", "user msg");
 *   Map map       = llm.callWithBase64ToMap(b64, "image/png", "system msg", "user msg");
 */
public class LLMService {

    private static final Logger logger = LoggerFactory.getLogger(LLMService.class);
    /** Same logical base as {@code LLM_BASE_URL=http://10.1.242.250/v1}; chat uses …/v1/chat/completions. */
    private static final String DEFAULT_API_URL = "http://10.1.242.250/v1/chat/completions";
    private static final String DEFAULT_MODEL = "qwen3-vl-30b";
    /** Used when {@code LLM_API_KEY} is unset; override via env in deployment. */
    private static final String DEFAULT_API_KEY = "87aed034064dad7fc743e26a8453c8747de203ccd696122e6d6b51526f8202d8";
    private static final String DEFAULT_EMBEDDING_MODEL = "Qwen/Qwen3-Embedding-0.6B";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final String apiUrl;
    private final String model;
    /** Env {@code LLM_API_KEY} when set, else {@link #DEFAULT_API_KEY}; sent as {@code Authorization: Bearer …}. */
    private final String apiKey;
    private final String embeddingApiUrl;
    private final String embeddingModel;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    // ─── constructors ─────────────────────────────────────────────────────────

    public LLMService() {
        this(resolveEnvApiUrl(), resolveEnvModel());
    }

    public LLMService(String apiUrl) {
        this(apiUrl, resolveEnvModel());
    }

    public LLMService(String apiUrl, String model) {
        this.apiUrl = (apiUrl != null && !apiUrl.isBlank()) ? apiUrl : DEFAULT_API_URL;
        this.model = (model != null && !model.isBlank()) ? model : DEFAULT_MODEL;
        this.apiKey = resolveEnvApiKey();
        this.embeddingApiUrl = resolveEmbeddingUrl(this.apiUrl);
        this.embeddingModel = resolveEmbeddingModel();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public List<Double> getEmbedding(String text) throws IOException {
        if (text == null || text.isBlank()) return List.of();

        Map<String, Object> body = new HashMap<>();
        body.put("model", embeddingModel);
        body.put("input", text);

        RequestBody rb = RequestBody.create(mapper.writeValueAsString(body), JSON_TYPE);
        try {
            List<Double> primary = tryEmbeddingOnce(embeddingApiUrl, rb);
            if (!primary.isEmpty()) return primary;

            String alt = embeddingApiUrl.endsWith("/") ? embeddingApiUrl.substring(0, embeddingApiUrl.length() - 1) : (embeddingApiUrl + "/");
            List<Double> secondary = tryEmbeddingOnce(alt, rb);
            if (!secondary.isEmpty()) return secondary;
        } catch (IOException ignored) {
        }
        return localHashEmbedding(text);
    }

    /**
     * Embeds multiple texts in one HTTP request (OpenAI-compatible: input = array of strings).
     * Much faster than calling {@link #getEmbedding(String)} per item for large indexes.
     * On failure, falls back to per-text embedding (or local hash).
     */
    public List<List<Double>> getEmbeddingsBatch(List<String> texts) throws IOException {
        if (texts == null || texts.isEmpty()) return List.of();
        List<String> cleaned = new ArrayList<>();
        for (String t : texts) {
            cleaned.add(t == null || t.isBlank() ? " " : t);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", embeddingModel);
        body.put("input", cleaned);

        RequestBody rb = RequestBody.create(mapper.writeValueAsString(body), JSON_TYPE);
        try {
            List<List<Double>> batch = tryEmbeddingBatchOnce(embeddingApiUrl, rb);
            if (!batch.isEmpty() && batch.size() == cleaned.size()) return batch;
        } catch (IOException ignored) {
        }
        try {
            String alt = embeddingApiUrl.endsWith("/") ? embeddingApiUrl.substring(0, embeddingApiUrl.length() - 1) : (embeddingApiUrl + "/");
            List<List<Double>> batch = tryEmbeddingBatchOnce(alt, rb);
            if (!batch.isEmpty() && batch.size() == cleaned.size()) return batch;
        } catch (IOException ignored) {
        }

        List<List<Double>> out = new ArrayList<>();
        for (String t : cleaned) {
            out.add(getEmbedding(t));
        }
        return out;
    }

    private List<Double> tryEmbeddingOnce(String url, RequestBody rb) throws IOException {
        Request request = authorize(new Request.Builder().url(url).post(rb)).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "";
                if (response.code() == 404) return List.of();
                throw new IOException("Embedding API error " + response.code() + ": " + err);
            }
            String raw = response.body() != null ? response.body().string() : "";
            return extractEmbedding(raw);
        }
    }

    private List<Double> localHashEmbedding(String text) {
        final int dim = 384;
        double[] v = new double[dim];
        String s = text.toLowerCase(Locale.ROOT);
        String[] parts = s.split("[^a-z0-9]+");
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty()) continue;
            int h = t.hashCode();
            int idx = (h & 0x7fffffff) % dim;
            double sign = ((h >>> 1) & 1) == 0 ? 1.0 : -1.0;
            v[idx] += sign;
        }
        double norm = 0.0;
        for (double x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm == 0.0) return List.of();
        List<Double> out = new ArrayList<>(dim);
        for (double x : v) out.add(x / norm);
        return out;
    }

    private List<Double> extractEmbedding(String responseBody) throws IOException {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) return List.of();
        JsonNode emb = data.get(0).get("embedding");
        if (emb == null || !emb.isArray()) return List.of();
        List<Double> out = new ArrayList<>(emb.size());
        for (JsonNode n : emb) out.add(n.asDouble());
        return out;
    }

    private List<List<Double>> tryEmbeddingBatchOnce(String url, RequestBody rb) throws IOException {
        Request request = authorize(new Request.Builder().url(url).post(rb)).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "";
                if (response.code() == 404) return List.of();
                throw new IOException("Embedding API error " + response.code() + ": " + err);
            }
            String raw = response.body() != null ? response.body().string() : "";
            return extractEmbeddingsBatch(raw);
        }
    }

    /** Parses OpenAI-style batch response: data[] in same order as request inputs. */
    private List<List<Double>> extractEmbeddingsBatch(String responseBody) throws IOException {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) return List.of();
        List<List<Double>> out = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode emb = item.get("embedding");
            if (emb == null || !emb.isArray()) continue;
            List<Double> vec = new ArrayList<>(emb.size());
            for (JsonNode e : emb) vec.add(e.asDouble());
            out.add(vec);
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  1. TEXT CHAT  →  String
    // ═══════════════════════════════════════════════════════════════════════════

    public String call(String systemPrompt, String userPrompt) throws IOException {
        return call(systemPrompt, userPrompt, 2048, 0.0);
    }

    public String call(String systemPrompt, String userPrompt, int maxTokens, double temperature) throws IOException {
        Map<String, Object> sysMsg = Map.of("role", "system", "content", safe(systemPrompt));
        Map<String, Object> userMsg = Map.of("role", "user", "content", safe(userPrompt));
        return post(sysMsg, userMsg, maxTokens, temperature);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  2. TEXT CHAT  →  Map<String, Object>
    // ═══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> callToMap(String systemPrompt, String userPrompt) throws IOException {
        return callToMap(systemPrompt, userPrompt, 2048, 0.0);
    }

    public Map<String, Object> callToMap(String systemPrompt, String userPrompt, int maxTokens, double temperature)
            throws IOException {
        String raw = call(systemPrompt, userPrompt, maxTokens, temperature);
        return parseToMap(raw);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  3. IMAGE FILE  →  String
    // ═══════════════════════════════════════════════════════════════════════════

    public String callWithImage(File imageFile, String systemPrompt, String userPrompt) throws IOException {
        return callWithImage(imageFile, systemPrompt, userPrompt, 4096, 0.0);
    }

    public String callWithImage(File imageFile, String systemPrompt, String userPrompt,
                                int maxTokens, double temperature) throws IOException {
        if (!imageFile.exists()) {
            throw new IOException("Image not found: " + imageFile.getAbsolutePath());
        }
        byte[] imgBytes = compressImage(imageFile, 1400, 0.80f);
        String base64 = Base64.getEncoder().encodeToString(imgBytes);
        String mime = mimeType(imageFile.getName());
        return sendVision(base64, mime, systemPrompt, userPrompt, maxTokens, temperature);
    }

    public String callWithImagePath(String imagePath, String systemPrompt, String userPrompt) throws IOException {
        return callWithImage(new File(imagePath), systemPrompt, userPrompt);
    }

    public String callWithImagePath(String imagePath, String systemPrompt, String userPrompt,
                                    int maxTokens, double temperature) throws IOException {
        return callWithImage(new File(imagePath), systemPrompt, userPrompt, maxTokens, temperature);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  4. IMAGE FILE  →  Map<String, Object>
    // ═══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> callWithImageToMap(File imageFile, String systemPrompt, String userPrompt)
            throws IOException {
        return callWithImageToMap(imageFile, systemPrompt, userPrompt, 4096, 0.0);
    }

    public Map<String, Object> callWithImageToMap(File imageFile, String systemPrompt, String userPrompt,
                                                  int maxTokens, double temperature) throws IOException {
        String raw = callWithImage(imageFile, systemPrompt, userPrompt, maxTokens, temperature);
        return parseToMap(raw);
    }

    public Map<String, Object> callWithImagePathToMap(String imagePath, String systemPrompt, String userPrompt)
            throws IOException {
        return callWithImageToMap(new File(imagePath), systemPrompt, userPrompt);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  5. BASE64 IMAGE  →  String
    // ═══════════════════════════════════════════════════════════════════════════

    public String callWithBase64(String base64Image, String mediaType,
                                 String systemPrompt, String userPrompt) throws IOException {
        return callWithBase64(base64Image, mediaType, systemPrompt, userPrompt, 4096, 0.0);
    }

    public String callWithBase64(String base64Image, String mediaType,
                                 String systemPrompt, String userPrompt,
                                 int maxTokens, double temperature) throws IOException {
        return sendVision(base64Image, mediaType, systemPrompt, userPrompt, maxTokens, temperature);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  6. BASE64 IMAGE  →  Map<String, Object>
    // ═══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> callWithBase64ToMap(String base64Image, String mediaType,
                                                   String systemPrompt, String userPrompt) throws IOException {
        return callWithBase64ToMap(base64Image, mediaType, systemPrompt, userPrompt, 4096, 0.0);
    }

    public Map<String, Object> callWithBase64ToMap(String base64Image, String mediaType,
                                                   String systemPrompt, String userPrompt,
                                                   int maxTokens, double temperature) throws IOException {
        String raw = callWithBase64(base64Image, mediaType, systemPrompt, userPrompt, maxTokens, temperature);
        return parseToMap(raw);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  JSON PARSING  (public so services can also use it if needed)
    // ═══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> parseToMap(String raw) {
        String json = cleanJson(raw);
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            logger.warn("JSON parse failed, returning raw wrapper: {}", e.getMessage());
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("raw", raw);
            return fallback;
        }
    }

    public String cleanJson(String text) {
        if (text == null) return "{}";
        String t = text.trim();
        t = t.replaceAll("(?s)```(?:json)?\\n?", "");
        t = t.replace("```", "").trim();
        int start = -1;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '{' || t.charAt(i) == '[') { start = i; break; }
        }
        int end = -1;
        for (int i = t.length() - 1; i >= 0; i--) {
            if (t.charAt(i) == '}' || t.charAt(i) == ']') { end = i + 1; break; }
        }
        if (start != -1 && end != -1 && end > start) return t.substring(start, end);
        Pattern p = Pattern.compile("(?s)(\\{.*\\}|\\[.*\\])");
        Matcher m = p.matcher(t);
        if (m.find()) return m.group(1);
        return t;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  INTERNALS
    // ═══════════════════════════════════════════════════════════════════════════

    private String sendVision(String base64, String mediaType,
                              String systemPrompt, String userPrompt,
                              int maxTokens, double temperature) throws IOException {
        String dataUri = "data:" + mediaType + ";base64," + base64;

        Map<String, Object> imageUrlPart = Map.of("type", "image_url", "image_url", Map.of("url", dataUri));
        Map<String, Object> textPart = Map.of("type", "text", "text", safe(userPrompt));

        Map<String, Object> sysMsg = Map.of("role", "system", "content", safe(systemPrompt));
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", new Object[]{imageUrlPart, textPart});

        return post(sysMsg, userMsg, maxTokens, temperature);
    }

    private String post(Map<String, Object> sysMsg, Map<String, Object> userMsg,
                        int maxTokens, double temperature) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", new Object[]{sysMsg, userMsg});
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("top_p", 0.9);
        body.put("stream", false);

        RequestBody rb = RequestBody.create(mapper.writeValueAsString(body), JSON_TYPE);
        Request request = authorize(new Request.Builder().url(apiUrl).post(rb)).build();

        logger.debug("LLM API call: {} tokens, temp={}", maxTokens, temperature);

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "";
                throw new IOException("LLM API error " + response.code() + ": " + err);
            }
            String raw = response.body() != null ? response.body().string() : "";
            return extractContent(raw);
        }
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            if (root.has("choices") && root.get("choices").isArray() && root.get("choices").size() > 0) {
                JsonNode first = root.get("choices").get(0);
                if (first.has("message") && first.get("message").has("content")) {
                    return first.get("message").get("content").asText();
                }
                if (first.has("text")) {
                    return first.get("text").asText();
                }
            }
        } catch (Exception ignored) {
        }
        return responseBody;
    }

    private byte[] compressImage(File imageFile, int maxDimension, float quality) throws IOException {
        BufferedImage img = ImageIO.read(imageFile);
        if (img == null) {
            return Files.readAllBytes(imageFile.toPath());
        }
        int w = img.getWidth(), h = img.getHeight();
        double scale = Math.min(1.0, (double) maxDimension / Math.max(w, h));
        int newW = Math.max(1, (int) Math.round(w * scale));
        int newH = Math.max(1, (int) Math.round(h * scale));

        BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, newW, newH, null);
        g.dispose();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            return Files.readAllBytes(imageFile.toPath());
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0f, Math.min(1f, quality)));
            }
            writer.write(null, new IIOImage(resized, null, null), param);
            ios.flush();
            return baos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private String mimeType(String fileName) {
        String n = fileName.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private String safe(String s) {
        return s != null ? s : "";
    }

    private Request.Builder authorize(Request.Builder b) {
        if (apiKey != null && !apiKey.isBlank()) {
            b.header("Authorization", "Bearer " + apiKey.trim());
        }
        return b;
    }

    /**
     * OpenAI-compatible base, e.g. {@code http://host/v1}. Becomes {@code …/v1/chat/completions}.
     * Set {@code LLM_BASE_URL} to override {@link #DEFAULT_API_URL}.
     */
    private static String resolveEnvApiUrl() {
        String env = System.getenv("LLM_BASE_URL");
        if (env == null || env.isBlank()) return DEFAULT_API_URL;
        String u = env.trim();
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/chat/completions")) return u;
        if (u.endsWith("/v1")) return u + "/chat/completions";
        if (!u.contains("/v1")) return u + "/v1/chat/completions";
        return u + "/chat/completions";
    }

    /** Uses {@code LLM_MODEL} when set; otherwise {@link #DEFAULT_MODEL}. */
    private static String resolveEnvModel() {
        String env = System.getenv("LLM_MODEL");
        if (env != null && !env.isBlank()) return env.trim();
        return DEFAULT_MODEL;
    }

    private static String resolveEnvApiKey() {
        String env = System.getenv("LLM_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        return DEFAULT_API_KEY;
    }

    private static String resolveEmbeddingUrl(String chatCompletionsUrl) {
        String env = System.getenv("EMBEDDING_API_URL");
        if (env != null && !env.isBlank()) return env.trim();
        if (chatCompletionsUrl == null || chatCompletionsUrl.isBlank())
            return DEFAULT_API_URL.replace("/v1/chat/completions", "/v1/embeddings");
        String u = chatCompletionsUrl.trim();
        if (u.contains("/v1/chat/completions")) return u.replace("/v1/chat/completions", "/v1/embeddings");
        if (u.endsWith("/v1/chat/completions")) return u.substring(0, u.length() - "/v1/chat/completions".length()) + "/v1/embeddings";
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/v1")) return u + "/embeddings";
        return u + "/v1/embeddings";
    }

    private static String resolveEmbeddingModel() {
        String env = System.getenv("EMBEDDING_MODEL");
        if (env != null && !env.isBlank()) return env.trim();
        return DEFAULT_EMBEDDING_MODEL;
    }

    // ─── LangChain4j / OpenAI-compatible SDKs (same env as this class) ─────────

    /** Base URL ending in {@code /v1} for LangChain4j OpenAI clients. */
    public static String openAiCompatibleBaseUrl() {
        String chat = resolveEnvApiUrl();
        if (chat.endsWith("/chat/completions")) {
            return chat.substring(0, chat.length() - "/chat/completions".length());
        }
        return chat;
    }

    public static String resolvedLlmApiKey() {
        return resolveEnvApiKey();
    }

    public static String resolvedChatModelName() {
        return resolveEnvModel();
    }

    public static String resolvedEmbeddingModelName() {
        return resolveEmbeddingModel();
    }

    /**
     * Base URL for LangChain4j {@code OpenAiEmbeddingModel} (it POSTs to {@code {base}/embeddings}).
     * Matches the same full embedding URL as {@link #resolveEmbeddingUrl(String)} applied to the chat URL,
     * so set {@code EMBEDDING_API_URL} if your embeddings live on a different host/port than chat.
     */
    public static String openAiCompatibleEmbeddingBaseUrl() {
        String full = resolveEmbeddingUrl(resolveEnvApiUrl());
        if (full == null || full.isBlank()) {
            return openAiCompatibleBaseUrl();
        }
        String u = full.trim();
        if (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.endsWith("/embeddings")) {
            return u.substring(0, u.length() - "/embeddings".length());
        }
        return openAiCompatibleBaseUrl();
    }
}
