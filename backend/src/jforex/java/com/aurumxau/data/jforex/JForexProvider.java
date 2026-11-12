package com.aurumxau.data.jforex;

import com.aurumxau.config.AppConfig;
import com.aurumxau.data.MarketDataProvider;
import com.aurumxau.data.ProviderHealth;
import com.aurumxau.model.Candle;
import com.aurumxau.model.DataQuality;
import com.aurumxau.model.Tick;
import com.aurumxau.model.Timeframe;
import com.aurumxau.util.Clock;
import com.dukascopy.api.Filter;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IHistory;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.OfferSide;
import com.dukascopy.api.Period;
import com.dukascopy.api.system.ClientFactory;
import com.dukascopy.api.system.IClient;
import com.dukascopy.api.system.ISystemListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Official Dukascopy JForex SDK adapter: authenticated live XAU/USD ticks and history.
 * Requires a Dukascopy demo or live account (AURUM_JFOREX_USER / AURUM_JFOREX_PASSWORD).
 * Runs only on the backend; credentials never reach the mobile app.
 * Loaded reflectively so the core build does not depend on the SDK.
 */
public final class JForexProvider implements MarketDataProvider {
    private static final Logger log = LoggerFactory.getLogger(JForexProvider.class);
    public static final String SOURCE = "DUKASCOPY_JFOREX";

    private final AppConfig cfg;
    private final Clock clock;
    private final ScheduledExecutorService reconnectExec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jforex-reconnect");
        t.setDaemon(true);
        return t;
    });

    private IClient client;
    private volatile IContext context;
    private volatile boolean connected;
    private volatile CountDownLatch strategyStarted = new CountDownLatch(1);
    private Listener listener;
    private long reconnects;
    private long backoffMs = 5_000;
    private volatile String lastError = "";
    private volatile long lastErrorAt;
    private volatile long ticks;
    private volatile boolean closing;

    public JForexProvider(AppConfig cfg, Clock clock) {
        this.cfg = cfg;
        this.clock = clock;
    }

    @Override public String name() { return "Dukascopy JForex SDK"; }
    @Override public Kind kind() { return Kind.LIVE_STREAM; }
    @Override public int pricePrecision() { return 3; }
    @Override public boolean isConnected() { return connected && client != null && client.isConnected(); }

    @Override
    public void start(Listener listener) throws Exception {
        if (cfg.jforexUser.isBlank() || cfg.jforexPassword.isBlank()) {
            throw new IllegalStateException("JForex mode requires AURUM_JFOREX_USER and AURUM_JFOREX_PASSWORD");
        }
        this.listener = listener;
        client = ClientFactory.getDefaultInstance();
        File cache = cfg.dataDir.resolve("jforex-cache").toFile();
        //noinspection ResultOfMethodCallIgnored
        cache.mkdirs();
        client.setCacheDirectory(cache);
        client.setSystemListener(new ISystemListener() {
            @Override public void onStart(long processId) {
                log.info("JForex strategy {} started", processId);
            }

            @Override public void onStop(long processId) {
                log.info("JForex strategy {} stopped", processId);
                context = null;
                strategyStarted = new CountDownLatch(1);
            }

            @Override public void onConnect() {
                connected = true;
                backoffMs = 5_000;
                log.info("JForex connected");
                listener.onConnectionChange(true, "JForex connected");
                if (client.getStartedStrategies().isEmpty()) startStrategy();
            }

            @Override public void onDisconnect() {
                connected = false;
                log.warn("JForex disconnected");
                listener.onConnectionChange(false, "JForex disconnected");
                if (!closing) scheduleReconnect();
            }
        });
        connect();
    }

    private void connect() throws Exception {
        log.info("Connecting to JForex ({})", cfg.jforexJnlpUrl.contains("/live/") ? "LIVE server" : "DEMO server");
        client.connect(cfg.jforexJnlpUrl, cfg.jforexUser, cfg.jforexPassword);
        for (int i = 0; i < 30 && !client.isConnected(); i++) Thread.sleep(1_000);
        if (!client.isConnected()) throw new IllegalStateException("JForex did not connect within 30 s");
        connected = true;
        client.setSubscribedInstruments(Set.of(Instrument.XAUUSD));
        if (client.getStartedStrategies().isEmpty()) startStrategy();
    }

    private void startStrategy() {
        client.startStrategy(new IStrategy() {
            @Override public void onStart(IContext ctx) {
                context = ctx;
                strategyStarted.countDown();
            }

            @Override public void onTick(Instrument instrument, ITick t) {
                if (instrument != Instrument.XAUUSD) return;
                ticks++;
                listener.onTick(new Tick("XAU/USD", SOURCE, t.getTime(), clock.nowMs(),
                        t.getBid(), t.getAsk(), t.getBidVolume(), t.getAskVolume()));
            }

            @Override public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) {}
            @Override public void onMessage(IMessage message) {}
            @Override public void onAccount(IAccount account) {}
            @Override public void onStop() {}
        });
    }

    private void scheduleReconnect() {
        long delay = backoffMs;
        backoffMs = Math.min(backoffMs * 2, 300_000);
        reconnectExec.schedule(() -> {
            if (closing || isConnected()) return;
            reconnects++;
            try {
                if (client.isReconnectAllowed()) {
                    log.info("JForex reconnect attempt {}", reconnects);
                    client.reconnect();
                } else {
                    log.info("JForex full re-login attempt {}", reconnects);
                    connect();
                }
            } catch (Exception e) {
                lastError = "reconnect failed: " + e.getMessage();
                lastErrorAt = clock.nowMs();
                log.warn("JForex reconnect failed: {}", e.toString());
                scheduleReconnect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private IContext awaitContext() throws Exception {
        if (!strategyStarted.await(60, TimeUnit.SECONDS) || context == null) {
            throw new IllegalStateException("JForex history not available (strategy not started)");
        }
        return context;
    }

    private static Period period(Timeframe tf) {
        return switch (tf) {
            case M1 -> Period.ONE_MIN;
            case M5 -> Period.FIVE_MINS;
            case M15 -> Period.FIFTEEN_MINS;
            case M30 -> Period.THIRTY_MINS;
            case H1 -> Period.ONE_HOUR;
            case H4 -> Period.FOUR_HOURS;
            case D1 -> Period.DAILY;
            default -> throw new IllegalArgumentException("derive " + tf + " from M1");
        };
    }

    @Override
    public List<Candle> loadCandles(Timeframe tf, long fromMs, long toMs) throws Exception {
        IContext ctx = awaitContext();
        IHistory history = ctx.getHistory();
        Period p = period(tf);
        long lastComplete = tf.floor(clock.nowMs()) - tf.millis;
        long from = tf.floor(fromMs) < fromMs ? tf.floor(fromMs) + tf.millis : tf.floor(fromMs);
        long to = Math.min(tf.floor(toMs - 1), lastComplete);
        List<Candle> out = new ArrayList<>();
        long chunk = Math.max(tf.millis * 1_000L, 86_400_000L);
        for (long s = from; s <= to; s += chunk) {
            long e = Math.min(to, s + chunk - tf.millis);
            final long cs = s, ce = e;
            List<IBar> bid = ctx.executeTask(() -> history.getBars(Instrument.XAUUSD, p, OfferSide.BID, Filter.WEEKENDS, cs, ce)).get();
            List<IBar> ask = ctx.executeTask(() -> history.getBars(Instrument.XAUUSD, p, OfferSide.ASK, Filter.WEEKENDS, cs, ce)).get();
            Map<Long, IBar> askBy = new HashMap<>();
            for (IBar a : ask) askBy.put(a.getTime(), a);
            for (IBar b : bid) {
                IBar a = askBy.get(b.getTime());
                if (a == null) continue;
                boolean flat = b.getVolume() <= 0 && b.getOpen() == b.getClose() && b.getHigh() == b.getLow();
                if (flat) continue; // SDK placeholder for a period without ticks: never treat as a candle
                out.add(new Candle(tf, b.getTime(), b.getOpen(), b.getHigh(), b.getLow(), b.getClose(),
                        a.getOpen(), a.getHigh(), a.getLow(), a.getClose(), b.getVolume() + a.getVolume(), 0,
                        tf.closeTime(b.getTime()) <= clock.nowMs(), SOURCE, DataQuality.OK));
            }
        }
        return out;
    }

    @Override
    public List<Tick> loadTicks(long fromMs, long toMs) throws Exception {
        IContext ctx = awaitContext();
        IHistory history = ctx.getHistory();
        List<Tick> out = new ArrayList<>();
        long now = clock.nowMs();
        for (long s = fromMs; s < toMs; s += 3_600_000L) {
            final long cs = s, ce = Math.min(toMs, s + 3_600_000L) - 1;
            List<ITick> chunk = ctx.executeTask(() -> history.getTicks(Instrument.XAUUSD, cs, ce)).get();
            for (ITick t : chunk) {
                out.add(new Tick("XAU/USD", SOURCE, t.getTime(), now, t.getBid(), t.getAsk(), t.getBidVolume(), t.getAskVolume()));
            }
        }
        return out;
    }

    @Override
    public ProviderHealth health() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("server", cfg.jforexJnlpUrl.contains("/live/") ? "LIVE" : "DEMO");
        d.put("ticksReceived", ticks);
        d.put("strategyRunning", context != null);
        d.put("pipValue", Instrument.XAUUSD.getPipValue());
        return new ProviderHealth(name(), kind(), isConnected(), lastError, lastErrorAt, reconnects, 0, 0, d);
    }

    @Override
    public void close() {
        closing = true;
        reconnectExec.shutdownNow();
        if (client != null) {
            for (Long id : client.getStartedStrategies().keySet()) client.stopStrategy(id);
            client.disconnect();
        }
    }
}
