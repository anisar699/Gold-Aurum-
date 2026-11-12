package com.aurumxau.data.demo;

import com.aurumxau.data.CandleAggregator;
import com.aurumxau.data.MarketDataProvider;
import com.aurumxau.data.ProviderHealth;
import com.aurumxau.market.MarketHours;
import com.aurumxau.market.Sessions;
import com.aurumxau.model.Candle;
import com.aurumxau.model.DataQuality;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;
import com.aurumxau.util.Clock;
import com.aurumxau.util.Prices;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Synthetic XAU/USD-like simulator for trying the app without data credentials.
 * Everything it produces is labelled DEMO / DEMO_DATA. The base price is deliberately not the
 * current market price. Never use demo output for trading decisions.
 */
public final class DemoProvider implements MarketDataProvider {
    public static final String SOURCE = "DEMO_SIMULATOR";
    private static final int SUBSTEPS = 12;

    private final Clock clock;
    private final boolean ignoreMarketHours;
    private final Random rnd;
    private final int historyDays;

    private double price = 2000.00;
    private double anchor = 2000.00;
    private int regime; // -1 down, 0 range, +1 up
    private int regimeLeft;
    private volatile boolean running;
    private volatile boolean connected;
    private ScheduledExecutorService exec;
    private List<Candle> history;
    private long historyEnd;
    private long ticksEmitted;

    public DemoProvider(Clock clock, long seed, boolean ignoreMarketHours, int historyDays) {
        this.clock = clock;
        this.rnd = new Random(seed);
        this.ignoreMarketHours = ignoreMarketHours;
        this.historyDays = historyDays;
    }

    @Override public String name() { return "Demo simulator (synthetic data)"; }
    @Override public Kind kind() { return Kind.DEMO; }
    @Override public int pricePrecision() { return 2; }
    @Override public boolean isConnected() { return connected; }

    private synchronized void ensureHistory() {
        if (history != null) return;
        long end = Timeframe.M1.floor(clock.nowMs());
        long start = end - historyDays * 86_400_000L;
        List<Candle> out = new ArrayList<>((int) ((end - start) / 60_000L));
        for (long t = start; t < end; t += 60_000L) {
            if (!ignoreMarketHours && !MarketHours.isOpen(t)) continue;
            out.add(simulateMinute(t));
        }
        history = out;
        historyEnd = end;
    }

    private Candle simulateMinute(long t) {
        double sigma = sigmaPerMinute(t) / Math.sqrt(SUBSTEPS);
        double o = price, h = price, l = price;
        double spreadSum = 0;
        for (int i = 0; i < SUBSTEPS; i++) {
            step(sigma);
            h = Math.max(h, price);
            l = Math.min(l, price);
            spreadSum += spread();
        }
        double c = price;
        double sp = Prices.round(spreadSum / SUBSTEPS, 2);
        return new Candle(Timeframe.M1, t,
                Prices.round(o, 2), Prices.round(h, 2), Prices.round(l, 2), Prices.round(c, 2),
                Prices.round(o + sp, 2), Prices.round(h + sp, 2), Prices.round(l + sp, 2), Prices.round(c + sp, 2),
                SUBSTEPS * 10, SUBSTEPS, true, SOURCE, DataQuality.DEMO);
    }

    private double sigmaPerMinute(long t) {
        double m = switch (Sessions.label(t)) {
            case "LONDON_NY_OVERLAP" -> 1.5;
            case "NEW_YORK" -> 1.25;
            case "LONDON" -> 1.1;
            case "ASIA" -> 0.6;
            default -> 0.5;
        };
        return 0.55 * m;
    }

    private void step(double sigma) {
        if (regimeLeft-- <= 0) {
            int r = rnd.nextInt(10);
            regime = r < 3 ? 1 : r < 6 ? -1 : 0;
            regimeLeft = (60 + rnd.nextInt(420)) * SUBSTEPS;
            anchor = price;
        }
        double drift = switch (regime) {
            case 1 -> 0.035 * sigma;
            case -1 -> -0.035 * sigma;
            default -> -0.002 * (price - anchor);
        };
        double shock = rnd.nextGaussian() * sigma;
        if (rnd.nextDouble() < 0.002) shock *= 4; // occasional liquidity-grab style spike
        price = Math.max(500, price + drift + shock);
    }

    private double spread() {
        double s = 0.20 + Math.abs(rnd.nextGaussian()) * 0.06;
        if (rnd.nextDouble() < 0.004) s += 0.5 + rnd.nextDouble() * 0.6;
        return s;
    }

    @Override
    public void start(Listener listener) {
        ensureHistory();
        running = true;
        connected = true;
        listener.onConnectionChange(true, "demo simulator started");
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "demo-ticks");
            th.setDaemon(true);
            return th;
        });
        exec.scheduleWithFixedDelay(() -> {
            if (!running) return;
            long now = clock.nowMs();
            if (!ignoreMarketHours && !MarketHours.isOpen(now)) return;
            double sigma;
            synchronized (this) {
                sigma = sigmaPerMinute(now) / Math.sqrt(90); // ~90 ticks per minute
                step(sigma);
            }
            double sp = Prices.round(spread(), 2);
            double bid = Prices.round(price, 2);
            ticksEmitted++;
            listener.onTick(new Tick("XAU/USD", SOURCE, now, now, bid, Prices.round(bid + sp, 2), 1, 1));
        }, 300, 650, TimeUnit.MILLISECONDS);
    }

    @Override
    public List<Candle> loadCandles(Timeframe tf, long fromMs, long toMs) {
        ensureHistory();
        List<Candle> m1 = history.stream().filter(c -> c.openTimeMs() >= Timeframe.D1.floor(fromMs) && c.openTimeMs() < toMs).toList();
        List<Candle> src = tf == Timeframe.M1 ? m1 : CandleAggregator.aggregate(m1, tf, historyEnd);
        return src.stream().filter(c -> c.complete() && c.openTimeMs() >= fromMs && c.openTimeMs() < toMs)
                .map(c -> c.withQuality(DataQuality.DEMO)).toList();
    }

    @Override
    public List<Tick> loadTicks(long fromMs, long toMs) {
        return List.of(); // the simulator has no tick archive
    }

    @Override
    public ProviderHealth health() {
        return new ProviderHealth(name(), kind(), connected, "", 0, 0, 0, 0,
                Map.of("ticksEmitted", ticksEmitted, "note", "SYNTHETIC DATA - not a market price"));
    }

    @Override
    public void close() {
        running = false;
        connected = false;
        if (exec != null) exec.shutdownNow();
    }
}
