package com.aurumxau.strategy;

import com.aurumxau.TestData;
import com.aurumxau.alerts.AlertService;
import com.aurumxau.analysis.AnalysisSnapshot;
import com.aurumxau.analysis.MarketAnalyzer;
import com.aurumxau.analysis.MarketView;
import com.aurumxau.data.CandleBook;
import com.aurumxau.market.MarketHours;
import com.aurumxau.model.Candle;
import com.aurumxau.model.FeedStatus;
import com.aurumxau.model.Text;
import com.aurumxau.model.Timeframe;
import com.aurumxau.news.CalendarProvider;
import com.aurumxau.news.EconomicEvent;
import com.aurumxau.news.NewsService;
import com.aurumxau.perf.PerformanceCalculator;
import com.aurumxau.perf.TradeResult;
import com.aurumxau.signal.Signal;
import com.aurumxau.signal.SignalEvent;
import com.aurumxau.store.Database;
import com.aurumxau.store.SignalStore;
import com.aurumxau.util.Clock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DecisionAndServicesTest {

    static CandleBook book;
    static List<Candle> m1;
    static AnalysisSnapshot snap;
    static long asOf;

    @BeforeAll
    static void setup() {
        m1 = TestData.walk(Timeframe.M1, 12_000, 77);
        book = new CandleBook(true);
        book.loadHistory(Timeframe.M1, m1);
        book.rebuildDerived(m1.get(m1.size() - 1).closeTimeMs());
        asOf = m1.get(m1.size() - 1).closeTimeMs();
        snap = new MarketAnalyzer(2).analyze(MarketView.live(book, asOf), m1.get(m1.size() - 1).close(), asOf);
    }

    static DecisionEngine.Input input(double spread, FeedStatus feed, DecisionEngine.NewsGate news, DecisionEngine.AccountState acct,
                                      StrategySettings s, String activeId) {
        double bid = m1.get(m1.size() - 1).close();
        return new DecisionEngine.Input(snap, book.candlesUpTo(Timeframe.M1, asOf, 400), book.candlesUpTo(Timeframe.M5, asOf, 400),
                bid, bid + spread, feed, asOf, s, news, acct, activeId, activeId == null ? null : "ACTIVE",
                activeId == null ? null : "LONG", true, true);
    }

    static List<String> blockerCodes(DecisionEngine.Result r) {
        return r.decision().blockers().stream().map(Text::code).toList();
    }

    @Test
    void spreadNewsFeedLimitsAndActiveSignalForceWait() {
        DecisionEngine e = new DecisionEngine(Strategies.all());
        StrategySettings s = StrategySettings.defaults();
        DecisionEngine.AccountState ok = new DecisionEngine.AccountState(0, 0);
        DecisionEngine.Result wide = e.decide(input(0.90, FeedStatus.LIVE, DecisionEngine.NewsGate.none(), ok, s, null));
        assertEquals("WAIT", wide.decision().action());
        assertTrue(blockerCodes(wide).contains("G_SPREAD"));

        DecisionEngine.NewsGate news = new DecisionEngine.NewsGate(true, Text.of("G_NEWS", "CPI", "CPI"), true, List.of());
        DecisionEngine.Result n = e.decide(input(0.3, FeedStatus.LIVE, news, ok, s, null));
        assertEquals("WAIT", n.decision().action());
        assertTrue(blockerCodes(n).contains("G_NEWS"));

        for (FeedStatus f : List.of(FeedStatus.STALE, FeedStatus.DISCONNECTED, FeedStatus.HISTORICAL_ONLY, FeedStatus.DELAYED, FeedStatus.MARKET_CLOSED)) {
            DecisionEngine.Result r = e.decide(input(0.3, f, DecisionEngine.NewsGate.none(), ok, s, null));
            assertEquals("WAIT", r.decision().action(), f.name());
            assertNull(r.accepted(), "no signal may be issued on " + f);
            assertTrue(blockerCodes(r).contains("G_FEED"));
        }
        DecisionEngine.Result daily = e.decide(input(0.3, FeedStatus.LIVE, DecisionEngine.NewsGate.none(), new DecisionEngine.AccountState(3, 0), s, null));
        assertTrue(blockerCodes(daily).contains("G_DAILY"));
        DecisionEngine.Result losses = e.decide(input(0.3, FeedStatus.LIVE, DecisionEngine.NewsGate.none(), new DecisionEngine.AccountState(1, 2), s, null));
        assertTrue(blockerCodes(losses).contains("G_LOSSES"));
        DecisionEngine.Result active = e.decide(input(0.3, FeedStatus.LIVE, DecisionEngine.NewsGate.none(), ok, s, "X1"));
        assertTrue(blockerCodes(active).contains("G_ACTIVE"));
        assertNull(active.accepted(), "only one primary setup at a time");
    }

    @Test
    void timeframeConflictReturnsWait() {
        AnalysisSnapshot.Bias b = new AnalysisSnapshot.Bias("RANGE", "BULLISH", "BEARISH", true, 0, List.of());
        AnalysisSnapshot conflicted = new AnalysisSnapshot(snap.generatedAtMs(), snap.dataAsOfMs(), snap.refPrice(), 2, snap.timeframes(),
                b, snap.zones(), snap.keyLevels(), snap.session(), snap.volatility(), false);
        DecisionEngine.Input base = input(0.3, FeedStatus.LIVE, DecisionEngine.NewsGate.none(), new DecisionEngine.AccountState(0, 0),
                StrategySettings.defaults(), null);
        DecisionEngine.Input in = new DecisionEngine.Input(conflicted, base.m1(), base.m5(), base.bid(), base.ask(), base.feed(), base.nowMs(),
                base.settings(), base.news(), base.account(), null, null, null, true, true);
        DecisionEngine.Result r = new DecisionEngine(Strategies.all()).decide(in);
        assertEquals("WAIT", r.decision().action());
        assertTrue(blockerCodes(r).contains("G_CONFLICT"));
        assertNull(r.accepted());
    }

    @Test
    void everyIssuedSetupHasCompleteFieldsAndMandatoryStop() {
        // Scan the synthetic history for any accepted setup and check its contract.
        DecisionEngine e = new DecisionEngine(Strategies.all());
        MarketAnalyzer an = new MarketAnalyzer(2);
        StrategySettings s = StrategySettings.defaults();
        int checked = 0;
        for (int i = 6_000; i < m1.size() && checked < 3; i += 3) {
            long t = m1.get(i).closeTimeMs();
            AnalysisSnapshot sn = an.analyze(MarketView.live(book, t), m1.get(i).close(), t);
            double bid = m1.get(i).bidClose();
            DecisionEngine.Result r = e.decide(new DecisionEngine.Input(sn, book.candlesUpTo(Timeframe.M1, t, 400),
                    book.candlesUpTo(Timeframe.M5, t, 400), bid, bid + 0.3, FeedStatus.LIVE, t, s, DecisionEngine.NewsGate.none(),
                    new DecisionEngine.AccountState(0, 0), null, null, null, true, true));
            TradeSetup a = r.accepted();
            if (a == null) {
                assertEquals("WAIT", r.decision().action());
                continue;
            }
            checked++;
            assertEquals(a.direction() == com.aurumxau.model.Direction.LONG ? "BUY" : "SELL", r.decision().action());
            assertTrue(Double.isFinite(a.stopLoss()), "stop loss is mandatory");
            int sg = a.direction().sign();
            assertTrue((a.entryRef() - a.stopLoss()) * sg > 0);
            assertTrue((a.tp1() - a.entryRef()) * sg > 0 && (a.tp2() - a.tp1()) * sg >= 0);
            assertTrue(a.netRrTp2() >= s.minNetRR());
            assertTrue(a.score() >= s.minScore() && a.score() <= 100);
            assertNotNull(a.activationRule());
            assertNotNull(a.invalidation());
            assertEquals("M1", a.confirmationTf());
            assertFalse(a.reasons().isEmpty());
        }
        assertTrue(checked >= 1, "the synthetic fixture must produce at least one issued setup to check");
    }

    // ------------------------------------------------------------------ news

    static final String FF_JSON = """
            [{"title":"CPI m/m","country":"USD","date":"2026-11-12T08:30:00-05:00","impact":"High","forecast":"0.3%","previous":"0.4%"},
             {"title":"German ZEW","country":"EUR","date":"2026-11-12T05:00:00-05:00","impact":"High","forecast":"","previous":""},
             {"title":"Crude Oil Inventories","country":"USD","date":"2026-11-12T10:30:00-05:00","impact":"Low","forecast":"","previous":""}]""";

    @Test
    void calendarParsingAndBlackoutWindow() {
        List<EconomicEvent> ev = CalendarProvider.parse(FF_JSON, "FAIRECONOMY");
        assertEquals(3, ev.size());
        EconomicEvent cpi = ev.get(0);
        assertEquals(Instant.parse("2026-11-12T13:30:00Z").toEpochMilli(), cpi.timeMs());
        assertEquals("INFLATION", cpi.category());
        assertTrue(cpi.goldRelevant());
        assertEquals("", cpi.actual(), "actual is not provided by the weekly feed and must not be invented");
        assertFalse(ev.get(1).goldRelevant(), "EUR event");

        Clock.Manual clock = new Clock.Manual(cpi.timeMs() - 20 * 60_000L);
        NewsService ns = new NewsService(null, clock, 60);
        ns.setEvents(ev);
        StrategySettings s = StrategySettings.defaults();
        assertFalse(ns.gate(clock.nowMs(), s).blocked(), "20 min before");
        clock.set(cpi.timeMs() - 14 * 60_000L);
        assertTrue(ns.gate(clock.nowMs(), s).blocked(), "14 min before");
        clock.set(cpi.timeMs() + 15 * 60_000L);
        assertTrue(ns.gate(clock.nowMs(), s).blocked(), "15 min after");
        clock.set(cpi.timeMs() + 16 * 60_000L);
        assertFalse(ns.gate(clock.nowMs(), s).blocked(), "16 min after");
        clock.set(ev.get(2).timeMs());
        assertFalse(ns.gate(clock.nowMs(), s).blocked(), "Low impact never blocks with High threshold");
    }

    // ------------------------------------------------------------------ store, alerts, performance

    @Test
    void journalIsAppendOnly(@TempDir Path dir) throws SQLException {
        Database db = new Database(dir.resolve("t.db"));
        SignalStore store = new SignalStore(db);
        Signal s = com.aurumxau.signal.SignalLifecycleTest.sig(com.aurumxau.model.Direction.LONG, 0.05, true, 50);
        SignalEvent armed = new SignalEvent(0, s.id(), s.createdAtMs(), null, "ARMED", "ARMED", Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0,
                Text.of("A", "a", "a"));
        store.insert(s, List.of(armed));
        assertThrows(SQLException.class, () -> db.update("UPDATE signals SET json = '{}' WHERE id = ?", s.id()));
        assertThrows(SQLException.class, () -> db.update("DELETE FROM signals WHERE id = ?", s.id()));
        assertThrows(SQLException.class, () -> db.update("UPDATE signal_events SET type = 'X'"));
        assertThrows(SQLException.class, () -> db.update("DELETE FROM signal_events"));
        SignalStore.Entry e = store.get(s.id());
        assertEquals(s.stopLoss(), e.signal().stopLoss(), "original stop preserved exactly");
        assertEquals("ARMED", e.state());
        assertTrue(Double.isNaN(e.events().get(0).bid()), "NaN round-trips through storage");
        assertNotNull(store.openSignal("PAPER"));
        db.close();
    }

    @Test
    void alertsAreDeduplicated(@TempDir Path dir) throws SQLException {
        Database db = new Database(dir.resolve("a.db"));
        AlertService a = new AlertService(db, Clock.SYSTEM, null, false);
        AtomicInteger sent = new AtomicInteger();
        a.setBroadcaster(x -> sent.incrementAndGet());
        Text t = Text.of("T", "t", "t");
        assertTrue(a.emit("SIGNAL_ARMED", "S1:ARMED:ARMED", "info", t, t, "S1", null));
        assertFalse(a.emit("SIGNAL_ARMED", "S1:ARMED:ARMED", "info", t, t, "S1", null));
        assertTrue(a.emit("SIGNAL_CLOSED", "S1:CLOSED:SL", "high", t, t, "S1", null));
        assertEquals(2, sent.get());
        assertThrows(IllegalArgumentException.class, () -> a.registerToken("not-a-token", "android", "ur"));
        a.registerToken("ExponentPushToken[abcdefghijklmnop]", "android", "en");
        assertEquals(List.of("ExponentPushToken[abcdefghijklmnop]"), a.tokens("en"));
        db.close();
    }

    @Test
    void performanceStatistics() {
        List<TradeResult> t = new ArrayList<>();
        double[] rs = {1.5, -1, -1, 2, -1, 0.5};
        for (int i = 0; i < rs.length; i++) {
            t.add(new TradeResult("T" + i, "PAPER", i % 2 == 0 ? "LSR" : "BRR", "LONG", i * 1000L, i * 1000L + 1, i * 1000L + 60_001,
                    rs[i] > 0 ? "TP2" : "SL", true, true, rs[i], 1.8, 0.1, "LONDON", "M5", "M1", 70));
        }
        t.add(new TradeResult("E", "PAPER", "LSR", "LONG", 9000, 0, 0, "EXPIRED", false, false, Double.NaN, 1.8, Double.NaN, "LONDON", "M5", "M1", 70));
        PerformanceCalculator.Report r = PerformanceCalculator.compute("PAPER", t);
        assertEquals(7, r.totalSetups());
        assertEquals(6, r.activated());
        assertEquals(6, r.closed());
        assertEquals(3, r.wins());
        assertEquals(0.5, r.winRate(), 1e-9, "the expired setup is not a trade and not a win");
        assertEquals(1.0 / 6, r.expectancyR(), 1e-3); // (1.5-1-1+2-1+0.5)/6
        assertEquals(4.0 / 3.0, r.profitFactor(), 0.01);
        assertEquals(2.0, r.maxDrawdownR(), 1e-9);
        assertEquals(2, r.maxConsecutiveLosses());
        assertEquals(1.0 / 7, r.expiredRate(), 1e-3);
        assertEquals(1.0, r.avgHoldMinutes(), 1e-9);
        assertEquals(3, r.byStrategy().get("LSR").trades());
    }

    // ------------------------------------------------------------------ market hours

    @Test
    void goldMarketHoursFollowNewYorkRollover() {
        assertTrue(MarketHours.isOpen(Instant.parse("2026-11-11T10:00:00Z").toEpochMilli()));      // Wed
        assertFalse(MarketHours.isOpen(Instant.parse("2026-11-11T22:30:00Z").toEpochMilli()));     // 17:30 NY daily break (EST)
        assertTrue(MarketHours.isOpen(Instant.parse("2026-11-11T23:01:00Z").toEpochMilli()));      // 18:01 NY
        assertFalse(MarketHours.isOpen(Instant.parse("2026-11-13T22:05:00Z").toEpochMilli()));     // Fri after 17:00 NY
        assertFalse(MarketHours.isOpen(Instant.parse("2026-11-14T12:00:00Z").toEpochMilli()));     // Sat
        assertFalse(MarketHours.isOpen(Instant.parse("2026-11-15T22:30:00Z").toEpochMilli()));     // Sun 17:30 NY
        assertTrue(MarketHours.isOpen(Instant.parse("2026-11-15T23:00:00Z").toEpochMilli()));      // Sun 18:00 NY
        // Summer time (EDT): break moves to 21:00-22:00 UTC
        assertFalse(MarketHours.isOpen(Instant.parse("2026-07-15T21:30:00Z").toEpochMilli()));
        assertTrue(MarketHours.isOpen(Instant.parse("2026-07-15T22:00:00Z").toEpochMilli()));
        assertEquals(Instant.parse("2026-11-15T23:00:00Z").toEpochMilli(),
                MarketHours.nextOpen(Instant.parse("2026-11-14T12:00:00Z").toEpochMilli()));
    }
}
