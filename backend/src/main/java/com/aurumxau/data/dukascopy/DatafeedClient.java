package com.aurumxau.data.dukascopy;

import com.aurumxau.util.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Polite HTTP client for Dukascopy's public historical datafeed files.
 * <p>
 * This endpoint is not documented as an API and is rate limited (HTTP 429). The client therefore:
 * sends one request at a time, waits {@code minIntervalMs} between requests, backs off exponentially
 * after a 429 (starting at 60 s, capped at 30 min), identifies itself honestly, and caches files for
 * fully elapsed periods on disk so each file is downloaded at most once.
 */
public final class DatafeedClient {
    private static final Logger log = LoggerFactory.getLogger(DatafeedClient.class);

    public static final class RateLimitedException extends IOException {
        public final long retryAtMs;

        public RateLimitedException(long retryAtMs) {
            super("Dukascopy datafeed rate limit active until " + java.time.Instant.ofEpochMilli(retryAtMs));
            this.retryAtMs = retryAtMs;
        }
    }

    /** Pluggable transport for tests. */
    public interface Transport {
        Response get(String url, String userAgent) throws IOException, InterruptedException;
    }

    public record Response(int status, byte[] body) {}

    private final String baseUrl;
    private final String userAgent;
    private final long minIntervalMs;
    private final Path cacheDir;
    private final Transport transport;
    private final Clock clock;

    private long lastRequestAt;
    private long backoffUntil;
    private long backoffMs = 60_000;
    private long requests, cacheHits, notFound, rateLimited, errors;
    private String lastError = "";
    private long lastErrorAt;
    private long lastSuccessAt;

    public DatafeedClient(String baseUrl, String userAgent, long minIntervalMs, Path cacheDir, Transport transport, Clock clock) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.userAgent = userAgent;
        this.minIntervalMs = minIntervalMs;
        this.cacheDir = cacheDir;
        this.transport = transport;
        this.clock = clock;
    }

    public static Transport httpTransport() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return (url, ua) -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(45))
                    .header("User-Agent", ua)
                    .GET().build();
            HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return new Response(r.statusCode(), r.body());
        };
    }

    /**
     * @param relPath   e.g. {@code XAUUSD/2026/10/11/BID_candles_min_1.bi5} (month is zero-based)
     * @param cacheable true only when the file covers a fully elapsed, published period
     * @return file bytes; an empty array means "no data for this period" (HTTP 404 or empty file)
     */
    public synchronized byte[] get(String relPath, boolean cacheable) throws IOException {
        Path cached = cacheDir.resolve(relPath);
        if (cacheable && Files.exists(cached)) {
            cacheHits++;
            return Files.readAllBytes(cached);
        }
        long now = clock.nowMs();
        if (now < backoffUntil) throw new RateLimitedException(backoffUntil);
        long wait = lastRequestAt + minIntervalMs - now;
        if (wait > 0) sleep(wait);

        String url = baseUrl + "/" + relPath;
        int attempts = 0;
        while (true) {
            attempts++;
            lastRequestAt = clock.nowMs();
            requests++;
            Response r;
            try {
                r = transport.get(url, userAgent);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } catch (IOException e) {
                recordError("network: " + e.getMessage());
                if (attempts >= 3) throw e;
                sleep(2_000L * attempts);
                continue;
            }
            switch (r.status()) {
                case 200 -> {
                    lastSuccessAt = clock.nowMs();
                    backoffMs = 60_000;
                    if (cacheable) {
                        Files.createDirectories(cached.getParent());
                        Path tmp = cached.resolveSibling(cached.getFileName() + ".tmp");
                        Files.write(tmp, r.body());
                        Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                    return r.body();
                }
                case 404 -> {
                    notFound++;
                    lastSuccessAt = clock.nowMs();
                    return new byte[0];
                }
                case 429 -> {
                    rateLimited++;
                    backoffUntil = clock.nowMs() + backoffMs;
                    recordError("HTTP 429 Too Many Requests; backing off " + backoffMs / 1000 + " s");
                    log.warn("Dukascopy datafeed returned 429; pausing requests for {} s", backoffMs / 1000);
                    backoffMs = Math.min(backoffMs * 2, 30 * 60_000L);
                    throw new RateLimitedException(backoffUntil);
                }
                default -> {
                    recordError("HTTP " + r.status() + " for " + relPath);
                    if (r.status() >= 500 && attempts < 3) {
                        sleep(3_000L * attempts);
                        continue;
                    }
                    throw new IOException("datafeed HTTP " + r.status() + " for " + relPath);
                }
            }
        }
    }

    private void recordError(String msg) {
        errors++;
        lastError = msg;
        lastErrorAt = clock.nowMs();
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    public synchronized Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requests", requests);
        m.put("cacheHits", cacheHits);
        m.put("notFound", notFound);
        m.put("rateLimited", rateLimited);
        m.put("errors", errors);
        m.put("backoffUntilMs", backoffUntil);
        m.put("lastSuccessAtMs", lastSuccessAt);
        return m;
    }

    public synchronized String lastError() { return lastError; }
    public synchronized long lastErrorAt() { return lastErrorAt; }
    public synchronized long requests() { return requests; }
    public synchronized long rateLimitedCount() { return rateLimited; }
    public synchronized long lastSuccessAt() { return lastSuccessAt; }
    public synchronized boolean backingOff() { return clock.nowMs() < backoffUntil; }
}
