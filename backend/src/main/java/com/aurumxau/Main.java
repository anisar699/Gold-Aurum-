package com.aurumxau;

import com.aurumxau.ai.AiClient;
import com.aurumxau.ai.AiService;
import com.aurumxau.alerts.AlertService;
import com.aurumxau.alerts.ExpoPushClient;
import com.aurumxau.analysis.MarketAnalyzer;
import com.aurumxau.api.ApiServer;
import com.aurumxau.backtest.BacktestService;
import com.aurumxau.broker.BrokerService;
import com.aurumxau.config.AppConfig;
import com.aurumxau.data.FeedMonitor;
import com.aurumxau.data.MarketDataProvider;
import com.aurumxau.data.MarketDataService;
import com.aurumxau.data.demo.DemoProvider;
import com.aurumxau.data.dukascopy.DatafeedClient;
import com.aurumxau.data.dukascopy.DatafeedProvider;
import com.aurumxau.news.CalendarProvider;
import com.aurumxau.news.NewsService;
import com.aurumxau.signal.SignalService;
import com.aurumxau.store.Database;
import com.aurumxau.store.KeyValueStore;
import com.aurumxau.store.SignalStore;
import com.aurumxau.strategy.DecisionEngine;
import com.aurumxau.strategy.SettingsService;
import com.aurumxau.strategy.Strategies;
import com.aurumxau.util.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wires and starts the backend. Configuration: environment variables (see backend/.env.example). */
public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> log.error("Uncaught exception in {}", t.getName(), e));
        AppConfig cfg;
        App app;
        try {
            cfg = AppConfig.load();
            app = start(cfg, Clock.SYSTEM);
        } catch (Exception e) {
            log.error("Startup failed: {}", e.getMessage() == null ? e.toString() : e.getMessage(), e);
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));
        log.info("AURUM XAU backend {} listening on {}:{} (mode {})", ApiServer.VERSION, cfg.bindHost, app.api().port(), cfg.dataMode);
        if (cfg.apiTokenGenerated) {
            log.info("Generated API token (enter it in the app's Settings). Stored in {}/api-token.txt", cfg.dataDir);
            System.out.println("\n=== AURUM API TOKEN: " + cfg.apiToken + " ===\n");
        }
    }

    public record App(ApiServer api, MarketDataService mds, Engine engine, Database db) implements AutoCloseable {
        @Override
        public void close() {
            try {
                api.stop();
                mds.close();
                db.close();
            } catch (Exception e) {
                log.warn("shutdown error: {}", e.toString());
            }
        }
    }

    public static App start(AppConfig cfg, Clock clock) throws Exception {
        Database db = new Database(cfg.dataDir.resolve("aurum.db"));
        boolean demo = cfg.dataMode == AppConfig.DataMode.DEMO;
        boolean ignoreHours = demo && cfg.demoIgnoreMarketHours;
        MarketDataProvider provider = provider(cfg, clock);
        FeedMonitor monitor = new FeedMonitor(provider.kind(), clock, cfg.liveMaxAgeMs, cfg.staleAfterMs, cfg.delayedLatencyMs, ignoreHours);
        MarketDataService.HistoryWindow window = demo
                ? new MarketDataService.HistoryWindow(10, 45, 45)
                : new MarketDataService.HistoryWindow(cfg.historyM1Days, cfg.historyH1Days, cfg.historyD1Days);
        MarketDataService mds = new MarketDataService(provider, monitor, clock, window);

        AlertService alerts = new AlertService(db, clock, new ExpoPushClient(cfg.expoAccessToken), cfg.pushEnabled);
        SignalStore store = new SignalStore(db);
        SignalService signals = new SignalService(store, alerts, clock, demo ? "DEMO" : "PAPER");
        SettingsService settings = new SettingsService(new KeyValueStore(db));
        CalendarProvider cal = switch (cfg.calendarProvider) {
            case "file" -> cfg.calendarFile == null ? null : new CalendarProvider.FileCalendar(cfg.calendarFile);
            case "none" -> null;
            default -> new CalendarProvider.FairEconomy(cfg.calendarUrl);
        };
        NewsService news = new NewsService(cal, clock, cfg.calendarRefreshMinutes);
        Engine engine = new Engine(mds, new MarketAnalyzer(provider.pricePrecision()), new DecisionEngine(Strategies.all()),
                signals, news, alerts, settings, clock, ignoreHours);
        AiService ai = new AiService(cfg.aiConfigured()
                ? new AiClient(cfg.aiProvider, cfg.aiBaseUrl, cfg.aiApiKey, cfg.aiModel, cfg.aiTimeoutSeconds) : null, clock);
        BrokerService broker = new BrokerService(clock, settings.get().commissionPerLot());
        BacktestService backtests = new BacktestService(provider, db, clock, settings::get, news, ignoreHours);

        ApiServer api = new ApiServer(new ApiServer.Services(cfg, clock, mds, engine, signals, store, settings, news, alerts,
                ai, broker, backtests, db));
        api.start(cfg.bindHost, cfg.port);
        engine.setBroadcaster((type, payload) -> api.hub().broadcast(type, payload));
        alerts.setBroadcaster(a -> api.hub().broadcast("alert", a));
        signals.setBroadcaster(m -> api.hub().broadcast("signal", m));
        engine.start();
        mds.start();
        return new App(api, mds, engine, db);
    }

    static MarketDataProvider provider(AppConfig cfg, Clock clock) throws Exception {
        return switch (cfg.dataMode) {
            case DEMO -> new DemoProvider(clock, cfg.demoSeed, cfg.demoIgnoreMarketHours, 45);
            case DATAFEED -> new DatafeedProvider(new DatafeedClient(cfg.datafeedBaseUrl, cfg.datafeedUserAgent,
                    cfg.datafeedMinIntervalMs, cfg.dataDir.resolve("datafeed-cache"), DatafeedClient.httpTransport(), clock), clock);
            case JFOREX -> {
                if (cfg.jforexUser.isBlank() || cfg.jforexPassword.isBlank()) {
                    throw new IllegalStateException("AURUM_DATA_MODE=jforex requires AURUM_JFOREX_USER and AURUM_JFOREX_PASSWORD");
                }
                try {
                    Class<?> c = Class.forName("com.aurumxau.data.jforex.JForexProvider");
                    yield (MarketDataProvider) c.getConstructor(AppConfig.class, Clock.class).newInstance(cfg, clock);
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("This build does not include the JForex SDK. Rebuild with: mvn -Pjforex package");
                }
            }
        };
    }
}
