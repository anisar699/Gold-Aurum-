package com.aurumxau.alerts;

import com.aurumxau.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends notifications through the Expo push service (which relays to Firebase Cloud Messaging on Android).
 * The Android app must be built with FCM credentials for remote push to arrive; see docs/SETUP_PUSH.md.
 */
public final class ExpoPushClient {
    private static final Logger log = LoggerFactory.getLogger(ExpoPushClient.class);
    private static final String URL = "https://exp.host/--/api/v2/push/send";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String accessToken;

    public ExpoPushClient(String accessToken) {
        this.accessToken = accessToken;
    }

    /** @return tokens Expo reported as no longer registered (to be removed) */
    public List<String> send(List<String> tokens, String title, String body, Map<String, Object> data) {
        List<String> dead = new ArrayList<>();
        if (tokens.isEmpty()) return dead;
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (String t : tokens) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("to", t);
            m.put("title", title);
            m.put("body", body);
            m.put("data", data);
            m.put("sound", "default");
            m.put("priority", "high");
            m.put("channelId", "signals");
            msgs.add(m);
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(msgs)));
            if (!accessToken.isBlank()) b.header("Authorization", "Bearer " + accessToken);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                log.warn("Expo push HTTP {}: {}", r.statusCode(), r.body());
                return dead;
            }
            JsonNode data2 = Json.read(r.body()).path("data");
            for (int i = 0; i < data2.size() && i < tokens.size(); i++) {
                JsonNode tkt = data2.get(i);
                if ("error".equals(tkt.path("status").asText()) && "DeviceNotRegistered".equals(tkt.path("details").path("error").asText())) {
                    dead.add(tokens.get(i));
                }
            }
        } catch (Exception e) {
            log.warn("Expo push failed: {}", e.toString());
        }
        return dead;
    }
}
