package com.aurumxau.api;

import com.aurumxau.market.Sessions;
import com.aurumxau.perf.TradeResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** CSV export of trade results and import of real trades (kept separate as source IMPORTED). */
final class Csv {
    private Csv() {}

    static String trades(List<TradeResult> rows) {
        StringBuilder sb = new StringBuilder("id,source,strategy,direction,created_utc,activated_utc,closed_utc,outcome,activated,r_multiple,planned_net_rr,cost_r,session,score\n");
        for (TradeResult t : rows) {
            sb.append(esc(t.id())).append(',').append(t.source()).append(',').append(t.strategy()).append(',').append(t.direction()).append(',')
                    .append(iso(t.createdAtMs())).append(',').append(iso(t.activatedAtMs())).append(',').append(iso(t.closedAtMs())).append(',')
                    .append(t.outcome()).append(',').append(t.activated()).append(',').append(num(t.r())).append(',')
                    .append(num(t.plannedRr())).append(',').append(num(t.costR())).append(',').append(t.session()).append(',').append(t.score()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Expected header: time_utc,direction,entry,exit,stop[,strategy]
     * time_utc as ISO-8601 (e.g. 2026-11-12T08:31:00Z); direction BUY|SELL.
     */
    static List<TradeResult> parseImported(String body, long nowMs) {
        List<TradeResult> out = new ArrayList<>();
        String[] lines = body.replace("\r", "").split("\n");
        if (lines.length < 2) throw new IllegalArgumentException("CSV needs a header and at least one row");
        String header = lines[0].trim().toLowerCase(Locale.ROOT);
        if (!header.startsWith("time_utc,direction,entry,exit,stop")) {
            throw new IllegalArgumentException("CSV header must start with: time_utc,direction,entry,exit,stop");
        }
        if (lines.length > 5_001) throw new IllegalArgumentException("at most 5000 rows per import");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] f = line.split(",");
            if (f.length < 5) throw new IllegalArgumentException("row " + i + ": expected at least 5 columns");
            try {
                long t = Instant.parse(f[0].trim()).toEpochMilli();
                String dir = f[1].trim().toUpperCase(Locale.ROOT);
                if (!dir.equals("BUY") && !dir.equals("SELL")) throw new IllegalArgumentException("direction must be BUY or SELL");
                double entry = Double.parseDouble(f[2]), exit = Double.parseDouble(f[3]), stop = Double.parseDouble(f[4]);
                double risk = Math.abs(entry - stop);
                if (!(risk > 0)) throw new IllegalArgumentException("stop must differ from entry");
                int sg = dir.equals("BUY") ? 1 : -1;
                double r = (exit - entry) * sg / risk;
                String strat = f.length > 5 ? f[5].trim() : "IMPORTED";
                out.add(new TradeResult("IMP-" + i + "-" + t, "IMPORTED", strat, dir.equals("BUY") ? "LONG" : "SHORT", t, t, t,
                        r > 0 ? "WIN" : r < 0 ? "LOSS" : "BREAKEVEN", true, true, Math.round(r * 1000) / 1000.0, Double.NaN, Double.NaN,
                        Sessions.label(t), "IMPORTED", "IMPORTED", 0));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("row " + i + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static String iso(long ms) {
        return ms <= 0 ? "" : Instant.ofEpochMilli(ms).toString();
    }

    private static String num(double v) {
        return Double.isFinite(v) ? String.valueOf(v) : "";
    }

    private static String esc(String s) {
        return s == null ? "" : s.contains(",") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
