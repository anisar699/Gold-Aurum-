package com.aurumxau.news;

import com.aurumxau.model.Text;
import com.aurumxau.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Source of economic-calendar events. */
public interface CalendarProvider {
    String name();

    List<EconomicEvent> fetch() throws Exception;

    /**
     * Parses the FairEconomy / ForexFactory weekly JSON shape:
     * [{"title","country","date":"2026-11-12T08:30:00-05:00","impact","forecast","previous"}].
     * An optional "actual" field is used if present.
     */
    static List<EconomicEvent> parse(String json, String source) {
        JsonNode arr = Json.read(json);
        if (!arr.isArray()) throw new IllegalArgumentException("calendar JSON must be an array");
        List<EconomicEvent> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String title = n.path("title").asText("").trim();
            String country = n.path("country").asText("").trim().toUpperCase(Locale.ROOT);
            String date = n.path("date").asText("");
            if (title.isEmpty() || date.isEmpty()) continue;
            long t;
            try {
                t = OffsetDateTime.parse(date).toInstant().toEpochMilli();
            } catch (RuntimeException e) {
                continue; // unparseable date: skip rather than guess
            }
            String impact = n.path("impact").asText("");
            String cat = category(title);
            boolean relevant = country.equals("USD") && (!cat.equals("OTHER") || impact.equalsIgnoreCase("High"));
            out.add(new EconomicEvent(country + "-" + t + "-" + Integer.toHexString(title.hashCode()), title, country, t, impact,
                    n.path("forecast").asText(""), n.path("previous").asText(""), n.path("actual").asText(""),
                    source, cat, relevant, implication(cat)));
        }
        return out;
    }

    static String category(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("cpi") || t.contains("pce") || t.contains("ppi") || t.contains("inflation")) return "INFLATION";
        if (t.contains("non-farm") || t.contains("nonfarm") || t.contains("employment") || t.contains("unemployment")
                || t.contains("jobless") || t.contains("jolts") || t.contains("adp") || t.contains("payroll")) return "LABOR";
        if (t.contains("fomc") || t.contains("federal funds") || t.contains("fed ") || t.startsWith("fed")
                || t.contains("powell") || t.contains("interest rate")) return "FED";
        if (t.contains("gdp")) return "GROWTH";
        if (t.contains("ism") || t.contains("pmi") || t.contains("retail sales")) return "ACTIVITY";
        return "OTHER";
    }

    /** General, well-known tendencies only (not predictions). */
    static Text implication(String cat) {
        return switch (cat) {
            case "INFLATION" -> Text.of("IMP_INF",
                    "Hotter-than-forecast inflation tends to lift USD and yields (pressure on gold); softer prints tend to support gold. Expect sharp two-way moves.",
                    "Forecast se zyada inflation aam tor par USD aur yields barhati hai (gold par dabao); kam inflation gold ko sahara deti hai. Dono taraf tez harkat mumkin.");
            case "LABOR" -> Text.of("IMP_LAB",
                    "Strong jobs data tends to strengthen USD (bearish gold); weak data tends to weaken USD (bullish gold). Spreads often widen at release.",
                    "Mazboot jobs data aam tor par USD ko mazboot karta hai (gold mandi); kamzor data USD ko kamzor (gold tezi). Release par spread barh jata hai.");
            case "FED" -> Text.of("IMP_FED",
                    "Hawkish Fed signals (higher-for-longer rates) tend to weigh on gold; dovish signals tend to support it. Volatility can persist for hours.",
                    "Fed ka sakht lehja (zyada der tak oonchi sharah) gold par dabao dalta hai; narm lehja gold ko sahara deta hai. Volatility ghanton reh sakti hai.");
            case "GROWTH" -> Text.of("IMP_GDP",
                    "Strong growth tends to support USD and yields (mild gold pressure); weak growth can increase safe-haven demand.",
                    "Mazboot growth USD aur yields ko sahara deti hai (gold par halka dabao); kamzor growth safe-haven demand barha sakti hai.");
            case "ACTIVITY" -> Text.of("IMP_ACT",
                    "Business-activity surprises move USD and yields; gold usually reacts in the opposite direction to USD.",
                    "Karobari activity ke surprise USD aur yields ko hilate hain; gold aksar USD ke ulat chalta hai.");
            default -> Text.of("IMP_OTHER", "Potential USD volatility; watch spreads.", "USD mein volatility mumkin; spread par nazar rakhein.");
        };
    }

    /** FairEconomy public weekly feed (current week only). Requested at most every 30 minutes. */
    final class FairEconomy implements CalendarProvider {
        private final String url;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        public FairEconomy(String url) {
            this.url = url;
        }

        @Override public String name() { return "FairEconomy weekly calendar feed"; }

        @Override
        public List<EconomicEvent> fetch() throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "AurumXAU-backend/1.0").GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IllegalStateException("calendar HTTP " + r.statusCode());
            return parse(r.body(), "FAIRECONOMY");
        }
    }

    /** Manually maintained JSON file in the same shape (useful when no feed is permitted). */
    final class FileCalendar implements CalendarProvider {
        private final Path file;

        public FileCalendar(Path file) {
            this.file = file;
        }

        @Override public String name() { return "Calendar file " + file.getFileName(); }

        @Override
        public List<EconomicEvent> fetch() throws Exception {
            return parse(Files.readString(file), "FILE");
        }
    }
}
