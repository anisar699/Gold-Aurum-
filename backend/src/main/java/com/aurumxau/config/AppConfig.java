package com.aurumxau.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Process configuration. Every value can be set through an environment variable
 * (e.g. {@code AURUM_DATA_MODE}) or the same key in lower-dotted form inside an optional
 * properties file pointed to by {@code AURUM_CONFIG} (e.g. {@code data.mode=jforex}).
 * Secrets (passwords, API keys) are never logged and never sent to the mobile app.
 */
public final class AppConfig {

    public enum DataMode { DEMO, DATAFEED, JFOREX }

    public final int port;
    public final String bindHost;
    public final Path dataDir;
    /** Bearer token the mobile app must send. Generated on first start when not configured. */
    public final String apiToken;
    public final boolean apiTokenGenerated;
    public final DataMode dataMode;

    // Dukascopy JForex SDK (official, authenticated)
    public final String jforexJnlpUrl;
    public final String jforexUser;
    public final String jforexPassword;

    // Dukascopy public historical datafeed (undocumented endpoint; throttled, cached, HISTORICAL ONLY)
    public final String datafeedBaseUrl;
    public final long datafeedMinIntervalMs;
    public final String datafeedUserAgent;

    // History bootstrap windows
    public final int historyM1Days;
    public final int historyH1Days;
    public final int historyD1Days;

    // Feed freshness thresholds
    public final long liveMaxAgeMs;
    public final long staleAfterMs;
    public final long delayedLatencyMs;

    public final boolean demoIgnoreMarketHours;
    public final long demoSeed;

    // AI explanations
    public final String aiProvider;     // none | openai | deepseek
    public final String aiApiKey;
    public final String aiModel;
    public final String aiBaseUrl;
    public final int aiTimeoutSeconds;

    // Economic calendar
    public final String calendarProvider; // fairEconomy | file | none
    public final String calendarUrl;
    public final Path calendarFile;
    public final int calendarRefreshMinutes;

    // Push notifications (Expo push service)
    public final boolean pushEnabled;
    public final String expoAccessToken;

    // Optional broker (MT5/Exness) quote bridge
    public final String brokerFeedToken;

    public final int rateLimitPerMinute;
    public final int aiRateLimitPerMinute;

    private AppConfig(Map<String, String> env, Properties props) throws IOException {
        Source s = new Source(env, props);
        this.port = s.integer("port", 8080);
        this.bindHost = s.str("bind.host", "0.0.0.0");
        this.dataDir = Path.of(s.str("data.dir", "./data")).toAbsolutePath().normalize();
        Files.createDirectories(dataDir);

        String token = s.str("api.token", "");
        boolean generated = false;
        if (token.isBlank()) {
            Path tokenFile = dataDir.resolve("api-token.txt");
            if (Files.exists(tokenFile)) {
                token = Files.readString(tokenFile).trim();
            } else {
                token = randomToken();
                Files.writeString(tokenFile, token);
                generated = true;
            }
        }
        if (token.length() < 16) throw new IllegalArgumentException("AURUM_API_TOKEN must be at least 16 characters");
        this.apiToken = token;
        this.apiTokenGenerated = generated;

        this.dataMode = DataMode.valueOf(s.str("data.mode", "demo").toUpperCase(Locale.ROOT));

        this.jforexJnlpUrl = s.str("jforex.jnlp.url", "https://platform.dukascopy.com/demo/jforex.jnlp");
        this.jforexUser = s.str("jforex.user", "");
        this.jforexPassword = s.str("jforex.password", "");

        this.datafeedBaseUrl = s.str("datafeed.base.url", "https://datafeed.dukascopy.com/datafeed");
        this.datafeedMinIntervalMs = s.lng("datafeed.min.interval.ms", 2500);
        this.datafeedUserAgent = s.str("datafeed.user.agent", "AurumXAU-backend/1.0 (self-hosted research tool)");

        this.historyM1Days = s.integer("history.m1.days", 5);
        this.historyH1Days = s.integer("history.h1.days", 60);
        this.historyD1Days = s.integer("history.d1.days", 400);

        this.liveMaxAgeMs = s.lng("feed.live.max.age.ms", 15_000);
        this.staleAfterMs = s.lng("feed.stale.after.ms", 60_000);
        this.delayedLatencyMs = s.lng("feed.delayed.latency.ms", 3_000);

        this.demoIgnoreMarketHours = s.bool("demo.ignore.market.hours", true);
        this.demoSeed = s.lng("demo.seed", 20261112L);

        this.aiProvider = s.str("ai.provider", "none").toLowerCase(Locale.ROOT);
        this.aiApiKey = s.str("ai.api.key", "");
        this.aiModel = s.str("ai.model", aiProvider.equals("deepseek") ? "deepseek-chat" : "gpt-5-mini");
        this.aiBaseUrl = s.str("ai.base.url", aiProvider.equals("deepseek") ? "https://api.deepseek.com" : "https://api.openai.com/v1");
        this.aiTimeoutSeconds = s.integer("ai.timeout.seconds", 60);

        this.calendarProvider = s.str("calendar.provider", "fairEconomy");
        this.calendarUrl = s.str("calendar.url", "https://nfs.faireconomy.media/ff_calendar_thisweek.json");
        String cf = s.str("calendar.file", "");
        this.calendarFile = cf.isBlank() ? null : Path.of(cf);
        this.calendarRefreshMinutes = s.integer("calendar.refresh.minutes", 60);

        this.pushEnabled = s.bool("push.enabled", true);
        this.expoAccessToken = s.str("expo.access.token", "");

        this.brokerFeedToken = s.str("broker.feed.token", "");

        this.rateLimitPerMinute = s.integer("rate.limit.per.minute", 240);
        this.aiRateLimitPerMinute = s.integer("ai.rate.limit.per.minute", 10);
    }

    public static AppConfig load() throws IOException {
        Properties props = new Properties();
        String file = System.getenv("AURUM_CONFIG");
        if (file != null && !file.isBlank()) {
            try (InputStream in = Files.newInputStream(Path.of(file))) {
                props.load(in);
            }
        }
        return new AppConfig(System.getenv(), props);
    }

    /** For tests: build from explicit key/values (property-style keys). */
    public static AppConfig of(Map<String, String> values) throws IOException {
        Properties p = new Properties();
        p.putAll(values);
        return new AppConfig(Map.of(), p);
    }

    public boolean aiConfigured() {
        return !aiProvider.equals("none") && !aiApiKey.isBlank();
    }

    /** Safe description for logs and /api/health (no secrets). */
    public Map<String, Object> describe() {
        return Map.of(
                "dataMode", dataMode.name(),
                "jforexConfigured", !jforexUser.isBlank() && !jforexPassword.isBlank(),
                "jforexServer", jforexJnlpUrl.contains("/live/") ? "LIVE" : "DEMO",
                "aiProvider", aiConfigured() ? aiProvider : "none",
                "aiModel", aiConfigured() ? aiModel : "",
                "calendarProvider", calendarProvider,
                "pushEnabled", pushEnabled,
                "brokerBridgeEnabled", !brokerFeedToken.isBlank());
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }

    private static final class Source {
        private final Map<String, String> env;
        private final Properties props;

        Source(Map<String, String> env, Properties props) {
            this.env = env;
            this.props = props;
        }

        String str(String key, String def) {
            String envKey = "AURUM_" + key.toUpperCase(Locale.ROOT).replace('.', '_');
            String v = env.get(envKey);
            if (v == null) v = props.getProperty(key);
            return v == null ? def : v.trim();
        }

        int integer(String key, int def) {
            return Integer.parseInt(str(key, Integer.toString(def)));
        }

        long lng(String key, long def) {
            return Long.parseLong(str(key, Long.toString(def)));
        }

        boolean bool(String key, boolean def) {
            return Boolean.parseBoolean(str(key, Boolean.toString(def)));
        }
    }
}
