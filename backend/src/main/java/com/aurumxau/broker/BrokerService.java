package com.aurumxau.broker;

import com.aurumxau.model.Tick;
import com.aurumxau.strategy.Decision;
import com.aurumxau.strategy.TradeSetup;
import com.aurumxau.util.Clock;
import com.aurumxau.util.Prices;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional broker (e.g. Exness MT5) price comparison. Quotes arrive from the user's own MT5 terminal through the
 * AurumBridge expert advisor (mt5/AurumBridge.mq5) with a separate token. Dukascopy stays the analysis source;
 * broker values are only shown when a fresh broker quote exists, never estimated.
 */
public final class BrokerService {
    public record BrokerQuote(String broker, String symbol, double bid, double ask, long timeMs, long receivedMs) {}

    private static final long FRESH_MS = 30_000;
    private final Clock clock;
    private final double commissionPerLot;
    private volatile BrokerQuote last;

    public BrokerService(Clock clock, double commissionPerLot) {
        this.clock = clock;
        this.commissionPerLot = commissionPerLot;
    }

    public void submit(String broker, String symbol, double bid, double ask, long timeMs) {
        if (!(bid > 0) || !(ask >= bid) || Double.isInfinite(ask)) throw new IllegalArgumentException("invalid broker quote");
        if (broker == null || broker.isBlank() || broker.length() > 40) throw new IllegalArgumentException("invalid broker name");
        long now = clock.nowMs();
        if (Math.abs(now - timeMs) > 5 * 60_000L) throw new IllegalArgumentException("broker quote timestamp is more than 5 minutes from server time");
        last = new BrokerQuote(broker, symbol == null ? "XAUUSD" : symbol, bid, ask, timeMs, now);
    }

    public Map<String, Object> compare(Tick duka, Decision decision, double contractSizeOz) {
        Map<String, Object> m = new LinkedHashMap<>();
        BrokerQuote b = last;
        long now = clock.nowMs();
        boolean fresh = b != null && now - b.receivedMs() < FRESH_MS;
        m.put("configured", b != null);
        m.put("fresh", fresh);
        if (!fresh || duka == null) {
            m.put("note", b == null ? "No broker quote source connected. Install mt5/AurumBridge.mq5 to compare prices."
                    : !fresh ? "Broker quote is stale (older than 30 s); broker values hidden."
                    : "No Dukascopy quote received yet; comparison unavailable.");
            return m;
        }
        double dMid = duka.mid(), bMid = (b.bid() + b.ask()) / 2, offset = bMid - dMid;
        m.put("broker", b.broker());
        m.put("brokerSymbol", b.symbol());
        m.put("dukascopy", Map.of("bid", duka.bid(), "ask", duka.ask(), "spread", Prices.r3(duka.spread()), "timeMs", duka.timeMs()));
        m.put("brokerQuote", Map.of("bid", b.bid(), "ask", b.ask(), "spread", Prices.r3(b.ask() - b.bid()), "timeMs", b.timeMs()));
        m.put("midDifference", Prices.r3(offset));
        m.put("spreadDifference", Prices.r3((b.ask() - b.bid()) - duka.spread()));
        double commPerOz = contractSizeOz > 0 ? commissionPerLot / contractSizeOz : 0;
        m.put("brokerExecutionCostPerOz", Prices.r3((b.ask() - b.bid()) + commPerOz));
        TradeSetup t = decision == null ? null : decision.setup();
        if (t != null && !"WAIT".equals(decision.action())) {
            m.put("adjustedSetup", Map.of(
                    "entryLow", Prices.r2(t.entryLow() + offset), "entryHigh", Prices.r2(t.entryHigh() + offset),
                    "stopLoss", Prices.r2(t.stopLoss() + offset), "tp1", Prices.r2(t.tp1() + offset), "tp2", Prices.r2(t.tp2() + offset),
                    "note", "Shifted by the current mid difference. Broker execution may differ; verify on your terminal."));
        }
        return m;
    }
}
