package com.aurumxau.analysis;

import com.aurumxau.model.Candle;
import com.aurumxau.model.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Market structure from confirmed swings.
 * <ul>
 *   <li>BOS (break of structure): a candle CLOSES beyond the latest unbroken swing in the trend direction.</li>
 *   <li>CHoCH (change of character): a candle CLOSES beyond the latest unbroken swing against the trend; the trend flips.</li>
 * </ul>
 * A swing can only be broken by candles after the one that confirmed it, and each swing level is consumed once.
 */
public final class Structure {
    private Structure() {}

    public enum Trend { UP, DOWN, NEUTRAL }

    public record Event(String type, Direction direction, int index, long timeMs, long confirmedAtMs, double level,
                        int brokenSwingIndex, long brokenSwingTimeMs, String scope) {}

    public record Result(Trend trend, List<Event> events, List<Swings.Swing> swings,
                         Swings.Swing lastHigh, Swings.Swing lastLow, String scope) {
        public Event lastEvent() {
            return events.isEmpty() ? null : events.get(events.size() - 1);
        }
    }

    public static Result analyze(List<Candle> c, int k, String scope) {
        List<Swings.Swing> swings = Swings.detect(c, k);
        List<Event> events = new ArrayList<>();
        Trend trend = Trend.NEUTRAL;
        Swings.Swing activeHigh = null, activeLow = null, lastHigh = null, lastLow = null;
        int si = 0;
        for (int i = 0; i < c.size(); i++) {
            while (si < swings.size() && swings.get(si).confirmedIndex() < i) {
                Swings.Swing s = swings.get(si++);
                if (s.high()) {
                    activeHigh = s;
                    lastHigh = s;
                } else {
                    activeLow = s;
                    lastLow = s;
                }
            }
            Candle k1 = c.get(i);
            if (activeHigh != null && k1.close() > activeHigh.price()) {
                String type = trend == Trend.DOWN ? "CHOCH" : "BOS";
                events.add(new Event(type, Direction.LONG, i, k1.openTimeMs(), k1.closeTimeMs(), activeHigh.price(),
                        activeHigh.index(), activeHigh.timeMs(), scope));
                trend = Trend.UP;
                activeHigh = null;
            }
            if (activeLow != null && k1.close() < activeLow.price()) {
                String type = trend == Trend.UP ? "CHOCH" : "BOS";
                events.add(new Event(type, Direction.SHORT, i, k1.openTimeMs(), k1.closeTimeMs(), activeLow.price(),
                        activeLow.index(), activeLow.timeMs(), scope));
                trend = Trend.DOWN;
                activeLow = null;
            }
        }
        return new Result(trend, events, swings, lastHigh, lastLow, scope);
    }
}
