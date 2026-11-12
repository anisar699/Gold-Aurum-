package com.aurumxau.signal;

import com.aurumxau.model.Direction;
import com.aurumxau.model.Text;
import com.aurumxau.strategy.ScoreCard;
import com.aurumxau.strategy.SetupMath;
import com.aurumxau.strategy.StrategySettings;
import com.aurumxau.strategy.TradeSetup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.aurumxau.TestData.T0;
import static org.junit.jupiter.api.Assertions.*;

public class SignalLifecycleTest {
    static final Text T = Text.of("X", "x", "x");

    /** LONG: entry zone 4000.00-4000.50 (ask), SL 3998.00, TP1 4002.30, TP2 4004.60 (bid). Slippage 0.05. */
    public static Signal sig(Direction d, double slip, boolean be, double tp1Pct) {
        boolean l = d == Direction.LONG;
        return new Signal("S1", T0, T0, "PAPER", null, d, "LSR", "Liquidity Sweep Reversal",
                l ? 4000.00 : 3999.50, l ? 4000.50 : 4000.00, l ? 4000.30 : 3999.70, T,
                l ? 3998.00 : 4002.00, l ? 4002.30 : 3997.40, l ? 4004.60 : 3995.10, 1, 2, 1.5,
                "M5", "M1", T0 + 5 * 60_000, 10, T, T, 70, new ScoreCard(List.of(), List.of(), 70), List.of(), List.of(),
                "LONDON", 0.30, "LIVE", "k", 0.01, 1, 5,
                new Signal.Execution(slip, 0.0, be, tp1Pct), "ARMED", null);
    }

    @Test
    void longFillsAtAskPlusSlippageAndScoresTp1PartialThenTp2() {
        SignalLifecycle l = new SignalLifecycle(sig(Direction.LONG, 0.05, true, 50));
        assertTrue(l.onQuote(4000.70, 4001.00, T0 + 1000).isEmpty(), "ask above zone: no chasing");
        List<SignalEvent> e = l.onQuote(4000.00, 4000.30, T0 + 2000);
        assertEquals("ACTIVATED", e.get(0).type());
        assertEquals(4000.35, l.fillPrice(), 1e-9, "BUY fills at ASK + slippage");
        e = l.onQuote(4002.30, 4002.60, T0 + 3000); // bid reaches TP1
        assertEquals("TP1_HIT", e.get(0).type());
        assertEquals("TP1_PARTIAL", l.state());
        assertEquals(4000.35, l.currentStop(), 1e-9, "breakeven stop after TP1");
        e = l.onQuote(4004.60, 4004.90, T0 + 4000);
        assertEquals("TP2", l.state());
        double risk = 4000.35 - 3998.00;
        double expected = 0.5 * (4002.30 - 4000.35) / risk + 0.5 * (4004.60 - 4000.35) / risk;
        assertEquals(expected, l.realizedR(), 1e-9);
        assertEquals(expected, e.get(e.size() - 1).rMultiple(), 1e-9);
    }

    @Test
    void longStopUsesBidAndGapFillsWorse() {
        SignalLifecycle l = new SignalLifecycle(sig(Direction.LONG, 0.05, true, 50));
        l.onQuote(4000.00, 4000.30, T0 + 1000);
        // ASK at 3998.10 is above the stop but BID gaps to 3997.50: the stop is on the BID side and fills at the worse price.
        List<SignalEvent> e = l.onQuote(3997.50, 3998.10, T0 + 2000);
        assertEquals("SL", l.state());
        assertEquals(3997.45, e.get(0).fillPrice(), 1e-9);
        assertTrue(l.realizedR() < -1.0, "gap through the stop loses more than 1R");
    }

    @Test
    void shortEntersAtBidExitsAtAsk() {
        SignalLifecycle l = new SignalLifecycle(sig(Direction.SHORT, 0.05, false, 50));
        l.onQuote(3999.80, 4000.10, T0 + 1000);
        assertEquals(3999.75, l.fillPrice(), 1e-9, "SELL fills at BID - slippage");
        assertTrue(l.onQuote(3997.20, 3997.60, T0 + 2000).isEmpty(), "bid below TP1 but ASK 3997.60 above TP1 3997.40: not hit");
        List<SignalEvent> e = l.onQuote(3997.00, 3997.40, T0 + 3000);
        assertEquals("TP1_HIT", e.get(0).type(), "short target is reached on the ASK");
        l.onQuote(4001.70, 4002.00, T0 + 4000);
        assertEquals("SL", l.state(), "no breakeven configured -> original stop on ASK; labelled SL, not TP1");
        assertTrue(l.realizedR() < 0.05, "TP1 partial does not turn a stopped trade into a big win");
    }

    @Test
    void unfilledSetupExpiresWithoutAnyR() {
        SignalLifecycle l = new SignalLifecycle(sig(Direction.LONG, 0.05, true, 50));
        l.onQuote(4001, 4001.3, T0 + 60_000);
        List<SignalEvent> e = l.onQuote(4001, 4001.3, T0 + 5 * 60_000);
        assertEquals("EXPIRED", l.state());
        assertTrue(Double.isNaN(e.get(0).rMultiple()));
        assertTrue(Double.isNaN(l.fillPrice()));
    }

    @Test
    void stopTouchedBeforeEntryInvalidates() {
        SignalLifecycle l = new SignalLifecycle(sig(Direction.LONG, 0.05, true, 50));
        l.onQuote(3997.90, 3998.20, T0 + 1000);
        assertEquals("INVALIDATED", l.state());
    }

    @Test
    void timeoutAtMaxHoldClosesAtMarket() {
        SignalLifecycle l = new SignalLifecycle(sig(Direction.LONG, 0.05, true, 50));
        l.onQuote(4000.00, 4000.30, T0 + 1000);
        assertTrue(l.onQuote(4001.00, 4001.30, T0 + 1000 + 9 * 60_000).isEmpty());
        List<SignalEvent> e = l.onQuote(4001.00, 4001.30, T0 + 1000 + 10 * 60_000);
        assertEquals("TIMEOUT", l.state());
        assertEquals(4000.95, e.get(0).fillPrice(), 1e-9, "closed at BID - slippage");
    }

    @Test
    void breakevenAfterTp1EndsAsTp1() {
        SignalLifecycle l = new SignalLifecycle(sig(Direction.LONG, 0.0, true, 50));
        l.onQuote(4000.00, 4000.30, T0 + 1000);
        l.onQuote(4002.30, 4002.60, T0 + 2000);
        l.onQuote(4000.30, 4000.60, T0 + 3000);
        assertEquals("TP1", l.state());
        assertTrue(l.realizedR() > 0);
    }

    @Test
    void restoreFromEventsReproducesState() {
        Signal s = sig(Direction.LONG, 0.05, true, 50);
        SignalLifecycle live = new SignalLifecycle(s);
        List<SignalEvent> log = new ArrayList<>();
        log.add(new SignalEvent(0, "S1", T0, null, "ARMED", "ARMED", Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, T));
        log.addAll(live.onQuote(4000.00, 4000.30, T0 + 1000));
        log.addAll(live.onQuote(4002.30, 4002.60, T0 + 2000));
        SignalLifecycle restored = SignalLifecycle.restore(s, log);
        assertEquals(live.state(), restored.state());
        assertEquals(live.fillPrice(), restored.fillPrice(), 1e-9);
        assertEquals(live.currentStop(), restored.currentStop(), 1e-9);
        assertEquals(live.holdUntilMs(), restored.holdUntilMs());
        live.onQuote(4004.60, 4004.90, T0 + 3000);
        restored.onQuote(4004.60, 4004.90, T0 + 3000);
        assertEquals(live.realizedR(), restored.realizedR(), 1e-6);
    }

    // ------------------------------------------------------------------ SetupMath

    @Test
    void setupMathLongIncludesSpreadAndCosts() {
        StrategySettings s = StrategySettings.defaults();
        SetupMath.Levels lv = SetupMath.compute(Direction.LONG, 4000.30, 3998.00, 1.2, 0.30, Double.NaN, s, 2);
        assertTrue(lv.ok());
        assertEquals(2.30, lv.risk(), 1e-9);
        assertEquals(4002.60, lv.tp1(), 1e-9);
        assertEquals(4004.90, lv.tp2(), 1e-9);
        double netRisk = 2.30 + 2 * 0.05;
        assertEquals(Math.round(((4004.90 - 4000.30) - 0.05) / netRisk * 100) / 100.0, lv.netRr2(), 1e-9);
        assertTrue(lv.entryLow() < lv.entryRef() && lv.entryHigh() > lv.entryRef());
    }

    @Test
    void setupMathRejectsWrongSideTooWideAndBlockedTargets() {
        StrategySettings s = StrategySettings.defaults();
        assertFalse(SetupMath.compute(Direction.LONG, 4000, 4001, 1, 0.3, Double.NaN, s, 2).ok(), "stop above long entry");
        assertFalse(SetupMath.compute(Direction.LONG, 4000, 3990, 1, 0.3, Double.NaN, s, 2).ok(), "10 > 3x ATR");
        SetupMath.Levels near = SetupMath.compute(Direction.LONG, 4000, 3998, 1.5, 0.3, 4002.5, s, 2);
        assertFalse(near.ok(), "resistance at +2.5 leaves < 1.3R");
        SetupMath.Levels shortOk = SetupMath.compute(Direction.SHORT, 4000, 4002, 1.5, 0.3, Double.NaN, s, 2);
        assertTrue(shortOk.ok());
        assertEquals(3998, shortOk.tp1(), 1e-9);
    }

    @Test
    void positionSizeRoundsDownToLotStep() {
        StrategySettings s = StrategySettings.defaults(); // equity 1000, risk 0.5% => $5
        TradeSetup.PositionSize p = SetupMath.size(2.0, s);
        assertEquals(5.0, p.riskUsd(), 1e-9);
        // net risk 2.10/oz -> 2.38 oz -> 0.0238 lots -> 0.02 lots
        assertEquals(0.02, p.lots(), 1e-9);
        TradeSetup.PositionSize tiny = SetupMath.size(20.0, s);
        assertTrue(tiny.belowMinLot());
        assertEquals(0, tiny.lots());
    }
}
