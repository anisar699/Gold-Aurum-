package com.aurumxau.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects price-like numbers in AI output that do not appear in the verified context.
 * Any number within +/-15 % of the reference price must match a context number within tolerance,
 * otherwise it is reported as unverified (the app shows a warning next to the AI text).
 */
public final class NumberGuard {
    private static final Pattern NUM = Pattern.compile("(?<![\\d.])(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?(?![\\d])");

    private NumberGuard() {}

    public static List<Double> numbers(String text) {
        List<Double> out = new ArrayList<>();
        Matcher m = NUM.matcher(text);
        while (m.find()) {
            String s = m.group(1).replace(",", "") + (m.group(2) == null ? "" : m.group(2));
            try {
                out.add(Double.parseDouble(s));
            } catch (NumberFormatException ignored) {
                // not a number
            }
        }
        return out;
    }

    public static List<Double> unverified(String aiText, String contextJson, double refPrice) {
        List<Double> out = new ArrayList<>();
        if (!(refPrice > 0)) return out;
        double lo = refPrice * 0.85, hi = refPrice * 1.15;
        List<Double> ctx = numbers(contextJson).stream().filter(v -> v >= lo && v <= hi).toList();
        double tol = Math.max(0.06, refPrice * 0.00002);
        for (double v : numbers(aiText)) {
            if (v < lo || v > hi) continue;
            boolean ok = false;
            for (double c : ctx) {
                if (Math.abs(c - v) <= tol) {
                    ok = true;
                    break;
                }
            }
            if (!ok && !out.contains(v)) out.add(v);
        }
        return out;
    }
}
