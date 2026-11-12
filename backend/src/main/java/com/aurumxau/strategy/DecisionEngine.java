package com.aurumxau.strategy;

import com.aurumxau.analysis.AnalysisSnapshot;
import com.aurumxau.analysis.KeyLevels;
import com.aurumxau.analysis.Liquidity;
import com.aurumxau.analysis.TimeframeAnalysis;
import com.aurumxau.analysis.Zones;
import com.aurumxau.market.MarketHours;
import com.aurumxau.market.Sessions;
import com.aurumxau.model.Candle;
import com.aurumxau.model.Direction;
import com.aurumxau.model.FeedStatus;
import com.aurumxau.model.Text;
import com.aurumxau.model.Timeframe;
import com.aurumxau.util.Prices;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Rules-based BUY / SELL / WAIT engine. A BUY or SELL is produced only when every hard gate passes,
 * a strategy's complete conditions are met on completed candles, levels give the minimum net R:R,
 * and the setup-quality score reaches the configured minimum. Otherwise the decision is WAIT with reasons.
 */
public final class DecisionEngine {

    public record NewsGate(boolean blocked, Text reason, boolean calendarAvailable, List<Text> upcomingRisks) {
        public static NewsGate none() {
            return new NewsGate(false, null, true, List.of());
        }
    }

    public record AccountState(int tradesToday, int consecutiveLosses) {}

    public record Input(AnalysisSnapshot snap, List<Candle> m1, List<Candle> m5, double bid, double ask,
                        FeedStatus feed, long nowMs, StrategySettings settings, NewsGate news, AccountState account,
                        String activeSignalId, String activeSignalState, String activeDirection,
                        boolean historyReady, boolean ignoreMarketHours) {}

    /** A candidate that was found but not issued, with the reason (kept for the journal). */
    public record Rejected(TradeSetup setup, Text reason, boolean recordable) {}

    public record Result(Decision decision, TradeSetup accepted, List<Rejected> rejected) {}

    private final List<Strategy> strategies;

    public DecisionEngine(List<Strategy> strategies) {
        this.strategies = strategies;
    }

    public Result decide(Input in) {
        AnalysisSnapshot snap = in.snap();
        StrategySettings s = in.settings();
        List<Text> blockers = new ArrayList<>();
        boolean recordableBlock = false;
        int pr = snap.pricePrecision();

        // ---- hard gates ----
        if (!in.historyReady()) blockers.add(t("G_HISTORY", "Historical data still loading", "Purana data abhi load ho raha hai"));
        if (!in.feed().allowsSignals()) {
            blockers.add(t("G_FEED", "No live data (" + in.feed() + "): new signals are paused",
                    "Live data nahi (" + in.feed() + "): naye signals band hain"));
        }
        for (Timeframe tf : List.of(Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.H1, Timeframe.H4)) {
            TimeframeAnalysis a = snap.tf(tf);
            int need = tf == Timeframe.H4 ? 30 : 60;
            if (a == null || a.candleCount() < need) {
                blockers.add(t("G_DATA_" + tf, "Not enough " + tf + " candles for analysis", tf + " candles analysis ke liye kam hain"));
            }
        }
        if (!in.ignoreMarketHours()) {
            if (!MarketHours.isOpen(in.nowMs())) {
                blockers.add(t("G_CLOSED", "Market closed", "Market band hai"));
            } else if (MarketHours.minutesToClose(in.nowMs()) < 15) {
                blockers.add(t("G_CLOSING", "Less than 15 minutes to the daily close/rollover", "Daily close/rollover mein 15 minute se kam"));
                recordableBlock = true;
            }
        }
        double spread = in.ask() - in.bid();
        if (Double.isFinite(spread) && spread > s.maxSpread()) {
            blockers.add(t("G_SPREAD", "Spread " + Prices.r2(spread) + " above limit " + s.maxSpread(),
                    "Spread " + Prices.r2(spread) + " had " + s.maxSpread() + " se zyada"));
            recordableBlock = true;
        }
        if (in.news().blocked()) {
            blockers.add(in.news().reason());
            recordableBlock = true;
        }
        if (!in.news().calendarAvailable() && s.newsFailClosed()) {
            blockers.add(t("G_NEWS_UNKNOWN", "Economic calendar unavailable (fail-closed setting)",
                    "Economic calendar dastiyab nahi (fail-closed setting)"));
        }
        if (in.account().tradesToday() >= s.maxDailyTrades()) {
            blockers.add(t("G_DAILY", "Daily trade limit reached (" + s.maxDailyTrades() + ")",
                    "Aaj ki trade limit poori (" + s.maxDailyTrades() + ")"));
            recordableBlock = true;
        }
        if (in.account().consecutiveLosses() >= s.maxConsecutiveLosses()) {
            blockers.add(t("G_LOSSES", in.account().consecutiveLosses() + " consecutive losses: paused until next day (PKT)",
                    in.account().consecutiveLosses() + " musalsal losses: agle din (PKT) tak signals band"));
            recordableBlock = true;
        }
        boolean active = in.activeSignalId() != null;
        if (active) blockers.add(t("G_ACTIVE", "A setup is already " + in.activeSignalState() + " (one primary setup at a time)",
                "Ek setup pehle se " + in.activeSignalState() + " hai (ek waqt mein ek setup)"));

        AnalysisSnapshot.Bias bias = snap.bias();
        List<Direction> dirs = new ArrayList<>();
        if (bias.conflict()) {
            blockers.add(t("G_CONFLICT", "H4 and H1 conflict: no trade", "H4 aur H1 mein ikhtilaf: trade nahi"));
        } else if (bias.overall().equals("BULLISH")) {
            dirs.add(Direction.LONG);
        } else if (bias.overall().equals("BEARISH")) {
            dirs.add(Direction.SHORT);
        } else if (s.allowRangeBias()) {
            dirs.add(Direction.LONG);
            dirs.add(Direction.SHORT);
        } else {
            blockers.add(t("G_RANGE", "No clear H4/H1 direction (RANGE)", "H4/H1 ki wazeh direction nahi (RANGE)"));
        }

        // ---- candidates ----
        List<Rejected> rejected = new ArrayList<>();
        List<TradeSetup> passing = new ArrayList<>();
        boolean canEvaluate = in.historyReady() && Double.isFinite(in.bid()) && Double.isFinite(in.ask())
                && snap.tf(Timeframe.M1) != null && snap.tf(Timeframe.M5) != null && snap.tf(Timeframe.M15) != null;
        if (canEvaluate) {
            StrategyContext ctx = new StrategyContext(snap, in.m1(), in.m5(), in.bid(), in.ask(), s, in.nowMs());
            for (Strategy st : strategies) {
                if (!s.enabledStrategies().contains(st.id())) continue;
                for (Direction d : dirs) {
                    Optional<Strategy.Candidate> c = st.evaluate(ctx, d);
                    if (c.isEmpty()) continue;
                    Built built = build(c.get(), ctx, bias, in);
                    if (built == null) continue;
                    TradeSetup setup = built.setup();
                    Text levelProblem = built.problem();
                    if (levelProblem != null) {
                        rejected.add(new Rejected(setup, levelProblem, true));
                    } else if (setup.score() < s.minScore()) {
                        rejected.add(new Rejected(setup, t("R_SCORE", "Score " + setup.score() + " below minimum " + s.minScore(),
                                "Score " + setup.score() + " kam az kam " + s.minScore() + " se kam"), true));
                    } else {
                        passing.add(setup);
                    }
                }
            }
        }
        passing.sort(Comparator.comparingInt(TradeSetup::score).reversed());
        TradeSetup best = passing.isEmpty() ? null : passing.get(0);

        List<Text> risks = risks(in, snap);
        List<Decision.NearLevel> nearby = nearby(snap, Double.isFinite(in.bid()) ? in.bid() : snap.refPrice(), pr);
        List<Text> reasons = new ArrayList<>(bias.reasons());

        TradeSetup accepted = null;
        String action = "WAIT";
        if (best != null && blockers.isEmpty()) {
            accepted = best;
            action = best.direction() == Direction.LONG ? "BUY" : "SELL";
            reasons.addAll(best.reasons());
            for (int i = 1; i < passing.size(); i++) {
                rejected.add(new Rejected(passing.get(i), t("R_LOWER", "Lower score than the primary setup",
                        "Primary setup se kam score"), true));
            }
        } else if (best != null) {
            Text first = blockers.get(0);
            for (TradeSetup p : passing) rejected.add(new Rejected(p, first, recordableBlock && !active));
        }
        if (accepted == null && best == null && blockers.isEmpty()) {
            reasons.add(t("W_NOSETUP", "No complete setup: waiting for an M5 sweep, retest or pullback at an M15 zone plus a closed M1 trigger",
                    "Koi mukammal setup nahi: M15 zone par M5 sweep/retest/pullback aur band M1 trigger ka intezar"));
        }
        if (active && in.activeDirection() != null) {
            action = in.activeDirection().equals("LONG") ? "BUY" : "SELL";
        }

        TradeSetup shown = accepted != null ? accepted : (best != null ? best : rejected.stream().map(Rejected::setup)
                .max(Comparator.comparingInt(TradeSetup::score)).orElse(null));
        int score = shown == null ? 0 : shown.score();
        Text summary = summary(action, active, blockers, accepted, bias, in.activeSignalState());
        Decision d = new Decision(action, in.nowMs(), snap.dataAsOfMs(), in.feed().name(), snap.demo(), bias.overall(),
                bias.h4(), bias.h1(), score, shown, reasons, blockers, risks, nearby, summary,
                in.activeSignalId(), in.activeSignalState());
        return new Result(d, accepted, rejected);
    }

    private record Built(TradeSetup setup, Text problem) {}

    private Built build(Strategy.Candidate c, StrategyContext ctx, AnalysisSnapshot.Bias bias, Input in) {
        Direction d = c.direction();
        double entryRef = d == Direction.LONG ? ctx.ask() : ctx.bid();
        double atrM1 = ctx.atrM1();
        if (!(atrM1 > 0)) return null;
        int pr = ctx.snap().pricePrecision();
        double opposing = ctx.opposingLevel(d, entryRef);
        SetupMath.Levels lv = SetupMath.compute(d, entryRef, c.structuralStop(), atrM1, ctx.spread(), opposing, ctx.settings(), pr);
        if (Double.isNaN(lv.entryRef())) {
            // Unusable levels: keep as a rejected record with the reason.
            lv = new SetupMath.Levels(entryRef, entryRef, entryRef, c.structuralStop(), Double.NaN, Double.NaN,
                    Math.abs(entryRef - c.structuralStop()), Double.NaN, Double.NaN, Double.NaN, lv.rejection());
        }
        StrategyContext.Confluence loc = ctx.confluence(d, c.locationPrice());
        ScoreCard sc = score(c, ctx, bias, loc, lv, in);
        List<Text> reasons = new ArrayList<>(c.reasons());
        reasons.addAll(loc.reasons());
        if (loc.points() <= 0) {
            lv = new SetupMath.Levels(lv.entryLow(), lv.entryHigh(), lv.entryRef(), lv.stop(), lv.tp1(), lv.tp2(), lv.risk(),
                    lv.netRr1(), lv.netRr2(), lv.blended(), t("R_LOCATION", "No M15 location confluence (OB/FVG/zone/key level)",
                    "M15 par koi location confluence nahi (OB/FVG/zone/key level)"));
        }
        List<Text> risks = new ArrayList<>();
        if (Double.isFinite(opposing)) {
            risks.add(t("RISK_OPPOSE", "Opposing liquidity/zone at " + Prices.round(opposing, pr),
                    "Saamne liquidity/zone " + Prices.round(opposing, pr) + " par"));
        }
        String dirTxt = Strategies.dirEn(d);
        Text activation = t("ACT", dirTxt + " when " + (d == Direction.LONG ? "ask" : "bid") + " is inside "
                        + lv.entryLow() + "-" + lv.entryHigh() + " before expiry; no chasing outside the zone",
                Strategies.dirUr(d) + " jab " + (d == Direction.LONG ? "ask" : "bid") + " " + lv.entryLow() + "-" + lv.entryHigh()
                        + " ke andar ho aur waqt baqi ho; zone se bahar peecha na karein");
        TradeSetup.PositionSize size = Double.isFinite(lv.risk()) ? SetupMath.size(lv.risk(), ctx.settings())
                : new TradeSetup.PositionSize(0, 0, 0, true, t("SIZE_NA", "No size: setup not valid", "Size nahi: setup durust nahi"));
        TradeSetup setup = new TradeSetup(c.strategy(), c.strategyName(), d, lv.entryLow(), lv.entryHigh(), lv.entryRef(),
                lv.stop(), lv.tp1(), lv.tp2(), lv.risk(), lv.netRr1(), lv.netRr2(), lv.blended(), c.setupTimeMs(),
                c.triggerTimeMs(), "M5", "M1", activation, c.invalidation(), c.requiredConfirmation(), sc.total(), sc,
                reasons, risks, c.patternKey(), size, Prices.r2(ctx.spread()));
        return new Built(setup, lv.rejection());
    }

    private ScoreCard score(Strategy.Candidate c, StrategyContext ctx, AnalysisSnapshot.Bias bias,
                            StrategyContext.Confluence loc, SetupMath.Levels lv, Input in) {
        ScoreCard.Builder sc = new ScoreCard.Builder();
        Direction d = c.direction();
        String want = d == Direction.LONG ? "BULLISH" : "BEARISH";
        double htf = bias.overall().equals(want) ? (bias.strength() == 2 ? 25 : 15) : bias.overall().equals("RANGE") ? 8 : 0;
        sc.add("HTF_ALIGNMENT", htf, 25, t("S_HTF", "H4 " + bias.h4() + " / H1 " + bias.h1(), "H4 " + bias.h4() + " / H1 " + bias.h1()));
        sc.add("LOCATION", loc.points(), 20, t("S_LOC", loc.reasons().size() + " M15 confluence factor(s)", loc.reasons().size() + " M15 confluence"));
        sc.add("SETUP", c.setupPoints(), 15, t("S_SETUP", c.strategyName() + " pattern clarity", c.strategyName() + " pattern ki wazahat"));
        sc.add("TRIGGER", c.triggerPoints(), 15, t("S_TRIG", "M1 confirmation candle body/close", "M1 tasdeeqi candle ki body/close"));
        TimeframeAnalysis m5 = ctx.tf(Timeframe.M5), m1 = ctx.tf(Timeframe.M1);
        int sg = d.sign();
        double mom = 0;
        if ((m5.rsi() - 50) * sg > 0) mom += 4;
        if (m5.macdHist() * sg > 0) mom += 3;
        if ((m1.macdHist() - m1.macdHistPrev()) * sg > 0) mom += 3;
        sc.add("MOMENTUM", mom, 10, t("S_MOM", "M5 RSI " + Math.round(m5.rsi()) + ", MACD agreement", "M5 RSI " + Math.round(m5.rsi()) + ", MACD ittefaq"));
        String regime = ctx.snap().volatility().regime();
        double vol = switch (regime) { case "NORMAL" -> 5; case "HIGH" -> 3; case "LOW" -> 1; default -> 0; };
        sc.add("VOLATILITY", vol, 5, t("S_VOL", "Volatility " + regime, "Volatility " + regime));
        double costRatio = Double.isFinite(lv.risk()) && lv.risk() > 0 ? ctx.spread() / lv.risk() : 1;
        double cost = costRatio <= 0.10 ? 5 : costRatio <= 0.20 ? 3 : costRatio <= 0.30 ? 1 : 0;
        sc.add("COST", cost, 5, t("S_COST", "Spread is " + Math.round(costRatio * 100) + "% of risk", "Spread risk ka " + Math.round(costRatio * 100) + "% hai"));
        sc.add("SESSION", Sessions.scorePoints(in.nowMs()), 5, t("S_SESS", "Session " + Sessions.label(in.nowMs()), "Session " + Sessions.label(in.nowMs())));
        if (bias.overall().equals("RANGE")) sc.penalty("RANGE_BIAS", 5, t("P_RANGE", "No HTF trend", "Bara trend nahi"));
        if (m1.partialData() || m5.partialData()) sc.penalty("PARTIAL_DATA", 10, t("P_PARTIAL", "Recent candles partially observed", "Haal ki candles ka data adhoora"));
        if (!in.news().calendarAvailable()) sc.penalty("NEWS_UNKNOWN", 10, t("P_NEWS", "Economic calendar unavailable", "Economic calendar dastiyab nahi"));
        return sc.build();
    }

    private List<Text> risks(Input in, AnalysisSnapshot snap) {
        List<Text> r = new ArrayList<>(in.news().upcomingRisks());
        double spread = in.ask() - in.bid();
        if (Double.isFinite(spread) && spread > 0.7 * in.settings().maxSpread() && spread <= in.settings().maxSpread()) {
            r.add(t("RISK_SPREAD", "Spread close to the limit (" + Prices.r2(spread) + ")", "Spread had ke qareeb (" + Prices.r2(spread) + ")"));
        }
        switch (snap.volatility().regime()) {
            case "HIGH" -> r.add(t("RISK_VOL", "High volatility: wider swings and slippage", "Zyada volatility: bare jhatke aur slippage"));
            case "EXTREME" -> r.add(t("RISK_VOLX", "Extreme volatility: avoid new scalps", "Intehai volatility: naye scalp se bachein"));
            case "LOW" -> r.add(t("RISK_VOLL", "Low volatility: targets may not be reached in 10 minutes", "Kam volatility: 10 minute mein target mushkil"));
            default -> {}
        }
        String sess = Sessions.label(in.nowMs());
        if (sess.equals("ASIA") || sess.equals("OFF_HOURS")) {
            r.add(t("RISK_SESSION", "Thin liquidity session (" + sess + ")", "Kam liquidity wala session (" + sess + ")"));
        }
        if (snap.demo()) r.add(t("RISK_DEMO", "DEMO DATA: synthetic prices, not tradable", "DEMO DATA: masnoi qeemat, trade ke liye nahi"));
        return r;
    }

    static List<Decision.NearLevel> nearby(AnalysisSnapshot snap, double price, int pr) {
        List<Decision.NearLevel> all = new ArrayList<>();
        if (!Double.isFinite(price)) return all;
        for (KeyLevels.KeyLevel k : snap.keyLevels()) all.add(new Decision.NearLevel(k.name(), k.price(), k.price() - price, k.side()));
        for (Timeframe tf : List.of(Timeframe.M15, Timeframe.H1)) {
            TimeframeAnalysis a = snap.tf(tf);
            if (a == null) continue;
            for (Liquidity.Pool p : a.pools()) all.add(new Decision.NearLevel(tf + " " + p.kind(), p.price(), p.price() - price, p.side()));
        }
        for (Zones.Zone z : snap.zones().stream().limit(6).toList()) {
            double edge = z.type().equals("RESISTANCE") ? z.low() : z.high();
            all.add(new Decision.NearLevel(z.type() + " " + z.sourceTf(), edge, edge - price, z.type().equals("RESISTANCE") ? "BUY_SIDE" : "SELL_SIDE"));
        }
        List<Decision.NearLevel> above = all.stream().filter(n -> n.distance() > 0).sorted(Comparator.comparingDouble(Decision.NearLevel::distance)).limit(3).toList();
        List<Decision.NearLevel> below = all.stream().filter(n -> n.distance() < 0).sorted(Comparator.comparingDouble(n -> -n.distance())).limit(3).toList();
        List<Decision.NearLevel> out = new ArrayList<>();
        for (Decision.NearLevel n : above) out.add(new Decision.NearLevel(n.name(), Prices.round(n.price(), pr), Prices.round(n.distance(), pr), n.side()));
        for (Decision.NearLevel n : below) out.add(new Decision.NearLevel(n.name(), Prices.round(n.price(), pr), Prices.round(n.distance(), pr), n.side()));
        return out;
    }

    private static Text summary(String action, boolean active, List<Text> blockers, TradeSetup accepted,
                                AnalysisSnapshot.Bias bias, String activeState) {
        if (active) {
            return t("SUM_ACTIVE", "Managing the current " + action + " setup (" + activeState + "). No new signals until it finishes.",
                    "Maujooda " + action + " setup (" + activeState + ") chal raha hai. Khatam hone tak naya signal nahi.");
        }
        if (accepted != null) {
            return t("SUM_TRADE", action + " setup (" + accepted.strategyName() + "), score " + accepted.score()
                            + "/100. Enter only inside the entry zone; stop is mandatory.",
                    action + " setup (" + accepted.strategyName() + "), score " + accepted.score()
                            + "/100. Sirf entry zone ke andar dakhil hon; stop lazmi hai.");
        }
        if (!blockers.isEmpty()) {
            Text b = blockers.get(0);
            return t("SUM_WAIT", "WAIT: " + b.en(), "WAIT: " + b.ur());
        }
        return t("SUM_WAIT_SETUP", "WAIT: bias " + bias.overall() + ", no complete setup yet.",
                "WAIT: bias " + bias.overall() + ", abhi koi mukammal setup nahi.");
    }

    static Text t(String code, String en, String ur) {
        return Text.of(code, en, ur);
    }
}
