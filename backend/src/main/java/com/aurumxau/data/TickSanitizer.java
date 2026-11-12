package com.aurumxau.data;

import com.aurumxau.model.Tick;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Validates the raw tick stream before it reaches candle aggregation.
 * <ul>
 *   <li>Rejects non-positive, non-finite and crossed (ask &lt; bid) quotes.</li>
 *   <li>Drops exact duplicates (same timestamp, bid and ask) seen recently.</li>
 *   <li>Drops out-of-order ticks (timestamp earlier than the last accepted tick).</li>
 *   <li>Quarantines single-tick spikes larger than {@code spikeFraction} of price; the move is accepted
 *       once {@code spikeConfirmTicks} consecutive ticks confirm the new level.</li>
 * </ul>
 * Not thread-safe: call from the single engine thread.
 */
public final class TickSanitizer {

    public enum Verdict { ACCEPTED, DUPLICATE, OUT_OF_ORDER, INVALID, SPIKE_QUARANTINED }

    private final double spikeFraction;
    private final int spikeConfirmTicks;
    private final int dedupeWindow;

    private long lastTime = Long.MIN_VALUE;
    private double lastMid = Double.NaN;
    private final Deque<Key> recent = new ArrayDeque<>();
    private final java.util.Set<Key> recentSet = new java.util.HashSet<>();
    private final Deque<Tick> spikeBuffer = new ArrayDeque<>();

    private long accepted, duplicates, outOfOrder, invalid, spikes;

    public TickSanitizer() {
        this(0.015, 3, 512);
    }

    public TickSanitizer(double spikeFraction, int spikeConfirmTicks, int dedupeWindow) {
        this.spikeFraction = spikeFraction;
        this.spikeConfirmTicks = spikeConfirmTicks;
        this.dedupeWindow = dedupeWindow;
    }

    private record Key(long t, double bid, double ask) {}

    public Verdict check(Tick t) {
        Objects.requireNonNull(t);
        if (!(t.bid() > 0) || !(t.ask() > 0) || Double.isInfinite(t.bid()) || Double.isInfinite(t.ask())
                || t.ask() < t.bid() || t.timeMs() <= 0) {
            invalid++;
            return Verdict.INVALID;
        }
        Key k = new Key(t.timeMs(), t.bid(), t.ask());
        if (recentSet.contains(k)) {
            duplicates++;
            return Verdict.DUPLICATE;
        }
        if (t.timeMs() < lastTime) {
            outOfOrder++;
            return Verdict.OUT_OF_ORDER;
        }
        double mid = t.mid();
        if (!Double.isNaN(lastMid) && Math.abs(mid - lastMid) / lastMid > spikeFraction) {
            // Possible bad print: hold until confirmed by consecutive ticks near the new level.
            if (!spikeBuffer.isEmpty()) {
                double ref = spikeBuffer.peekFirst().mid();
                if (Math.abs(mid - ref) / ref > spikeFraction / 3) spikeBuffer.clear();
            }
            spikeBuffer.addLast(t);
            if (spikeBuffer.size() < spikeConfirmTicks) {
                spikes++;
                return Verdict.SPIKE_QUARANTINED;
            }
            spikeBuffer.clear(); // confirmed regime change
        } else {
            spikeBuffer.clear();
        }
        remember(k);
        lastTime = t.timeMs();
        lastMid = mid;
        accepted++;
        return Verdict.ACCEPTED;
    }

    private void remember(Key k) {
        recent.addLast(k);
        recentSet.add(k);
        while (recent.size() > dedupeWindow) recentSet.remove(recent.removeFirst());
    }

    /** Reset ordering state, e.g. after a reconnect when the provider replays from a known point. */
    public void resetOrdering() {
        spikeBuffer.clear();
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accepted", accepted);
        m.put("duplicatesDropped", duplicates);
        m.put("outOfOrderDropped", outOfOrder);
        m.put("invalidDropped", invalid);
        m.put("spikesQuarantined", spikes);
        return m;
    }
}
