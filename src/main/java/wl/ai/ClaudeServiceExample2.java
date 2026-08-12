package wl.ai;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Simple wrapper to call a local chat-completions endpoint.
 * All API requests go through {@link LLMService}.
 * All methods return Map<String, Object>.
 */
public class ClaudeServiceExample2 {

    private final LLMService llmService;
    private final String systemPrompt;

    public ClaudeServiceExample2(String endpoint, String systemPrompt) {
        this(endpoint, systemPrompt, Duration.ofSeconds(30));
    }

    public ClaudeServiceExample2(String endpoint, String systemPrompt, Duration timeout) {
        this.systemPrompt = systemPrompt;
        this.llmService = new LLMService(endpoint);
    }

    public Map<String, Object> callModel(String userText) throws IOException {
        return llmService.callToMap(systemPrompt, userText);
    }

    public Map<String, Object> convertToJson(String text) throws IOException {
        return llmService.callToMap(systemPrompt, text);
    }

    public static void main(String[] args) throws Exception {
        String endpoint = "http://10.1.242.250:8000/v1/chat/completions";
        String system = "Return strictly a JSON object mapping state->capital";
        ClaudeServiceExample2 svc = new ClaudeServiceExample2(endpoint, system);
        Map<String, Object> out = svc.convertToJson("Give state->capital for Texas and California");
        System.out.println(out);
    }
}
