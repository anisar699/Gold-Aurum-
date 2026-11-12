package com.aurumxau.ai;

import com.aurumxau.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible chat-completions client (OpenAI and DeepSeek both expose this API).
 * The API key stays on the backend; the mobile app never sees it.
 */
public final class AiClient {
    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public AiClient(String provider, String baseUrl, String apiKey, String model, int timeoutSeconds) {
        this.provider = provider;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public String model() {
        return model;
    }

    public String provider() {
        return provider;
    }

    public String complete(String system, String user, int maxTokens) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        if (provider.equals("openai")) {
            body.put("max_completion_tokens", maxTokens);
        } else {
            body.put("max_tokens", maxTokens);
            body.put("temperature", 0.3);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            String msg = r.body().length() > 300 ? r.body().substring(0, 300) : r.body();
            throw new IllegalStateException("AI provider HTTP " + r.statusCode() + ": " + msg);
        }
        JsonNode n = Json.read(r.body());
        String content = n.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) throw new IllegalStateException("AI provider returned an empty answer");
        return content.trim();
    }
}
