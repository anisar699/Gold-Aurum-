package com.aurumxau.ai;

import com.aurumxau.analysis.AnalysisSnapshot;
import com.aurumxau.analysis.TimeframeAnalysis;
import com.aurumxau.analysis.Zones;
import com.aurumxau.model.Text;
import com.aurumxau.model.Timeframe;
import com.aurumxau.perf.PerformanceCalculator;
import com.aurumxau.strategy.Decision;
import com.aurumxau.strategy.TradeSetup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Deterministic explanation used when no AI provider is configured. Built only from engine output. */
final class RuleExplainer {
    private RuleExplainer() {}

    static String explain(AiService.Context ctx, boolean ur, boolean advanced) {
        AnalysisSnapshot s = ctx.snap();
        Decision d = ctx.decision();
        List<String> l = new ArrayList<>();
        String status = String.valueOf(ctx.quote().get("status"));
        l.add(ur ? "[Rule-based wazahat - AI configure nahi]" : "[Rule-based explanation - AI not configured]");
        l.add(ur ? "Data: " + status + " (" + ctx.quote().get("source") + ")" + (status.equals("LIVE") ? "" : " - ye live qeemat nahi")
                : "Data: " + status + " (" + ctx.quote().get("source") + ")" + (status.equals("LIVE") ? "" : " - not a live price"));
        AnalysisSnapshot.Bias b = s.bias();
        l.add(ur ? "Bias: H4 " + b.h4() + ", H1 " + b.h1() + " => " + b.overall() + (b.conflict() ? " (timeframes mein ikhtilaf)" : "")
                : "Bias: H4 " + b.h4() + ", H1 " + b.h1() + " => " + b.overall() + (b.conflict() ? " (timeframes conflict)" : ""));
        Zones.Zone sup = null, res = null;
        for (Zones.Zone z : s.zones()) {
            if (z.type().equals("SUPPORT") && (sup == null || z.distance() < sup.distance())) sup = z;
            if (z.type().equals("RESISTANCE") && (res == null || z.distance() < res.distance())) res = z;
        }
        if (sup != null || res != null) {
            String sTxt = sup == null ? "-" : sup.low() + "-" + sup.high() + " (" + sup.sourceTf() + ", " + Math.round(sup.strength()) + ")";
            String rTxt = res == null ? "-" : res.low() + "-" + res.high() + " (" + res.sourceTf() + ", " + Math.round(res.strength()) + ")";
            l.add(ur ? "Qareebi support: " + sTxt + " | Qareebi resistance: " + rTxt : "Nearest support: " + sTxt + " | Nearest resistance: " + rTxt);
        }
        if (d != null) {
            l.add((ur ? "Faisla: " : "Decision: ") + d.action() + " - " + pick(d.summary(), ur));
            TradeSetup t = d.setup();
            if (t != null && !"WAIT".equals(d.action())) {
                l.add((ur ? "Entry " : "Entry ") + t.entryLow() + "-" + t.entryHigh() + ", SL " + t.stopLoss() + ", TP1 " + t.tp1()
                        + ", TP2 " + t.tp2() + ", net R:R " + t.netRrTp2() + (ur ? " (score " : " (score ") + t.score() + "/100)");
            }
            List<Text> risks = new ArrayList<>(d.blockers());
            risks.addAll(d.risks());
            if (!risks.isEmpty()) {
                StringBuilder sb = new StringBuilder(ur ? "Khatrat: " : "Risks: ");
                for (int i = 0; i < Math.min(advanced ? 6 : 2, risks.size()); i++) sb.append(i == 0 ? "" : "; ").append(pick(risks.get(i), ur));
                l.add(sb.toString());
            }
        }
        if (advanced) {
            for (Timeframe tf : List.of(Timeframe.H4, Timeframe.H1, Timeframe.M15, Timeframe.M5, Timeframe.M1)) {
                TimeframeAnalysis a = s.tf(tf);
                if (a == null) continue;
                String ev = a.events().isEmpty() ? "-" : a.events().get(a.events().size() - 1).type() + " " + a.events().get(a.events().size() - 1).direction();
                l.add(tf + ": trend " + a.trend() + ", internal " + a.internalTrend() + ", EMA " + a.emaAlignment()
                        + ", RSI " + Math.round(a.rsi()) + ", ATR " + round3(a.atr()) + ", last " + ev
                        + ", OB " + a.orderBlocks().size() + ", FVG " + a.fvgs().size() + ", liquidity pools " + a.pools().size());
            }
            l.add((ur ? "Volatility: " : "Volatility: ") + s.volatility().regime() + " (M1 ATR " + round3(s.volatility().atrM1()) + ")");
        }
        l.add(ur ? "Score qawaid par mabni hai, munafe ki zamanat nahi. Stop loss lazmi."
                : "The score is rules-based, not a profit probability. Stop loss is mandatory.");
        return String.join("\n", l);
    }

    static String review(PerformanceCalculator.Report p, boolean ur) {
        List<String> l = new ArrayList<>();
        l.add(ur ? "[Rule-based performance review]" : "[Rule-based performance review]");
        if (p == null || p.closed() == 0) {
            l.add(ur ? "Abhi koi band trade nahi; jaiza mumkin nahi." : "No closed trades yet; nothing to review.");
            return String.join("\n", l);
        }
        l.add((ur ? "Band trades: " : "Closed trades: ") + p.closed() + ", win rate " + pct(p.winRate()) + ", expectancy " + p.expectancyR()
                + "R, profit factor " + p.profitFactor() + ", max drawdown " + p.maxDrawdownR() + "R");
        if (p.closed() < 20) l.add(ur ? "- Sample chhota hai (<20 trades): natije par bharosa kam rakhein." : "- Small sample (<20 trades): treat conclusions as tentative.");
        if (p.expiredRate() > 0.5) l.add(ur ? "- Zyada tar setups bina entry expire: entry zone/validity ko backtest mein test karein."
                : "- Most setups expire unfilled: test a wider entry zone or longer validity in a backtest first.");
        if (p.avgCostR() > 0.25) l.add(ur ? "- Kharcha (spread+slippage) risk ka 25% se zyada: spread filter sakht karein ya bade stop wale setups lein."
                : "- Costs exceed 25% of risk: tighten the spread filter or require larger structural stops.");
        Integer timeouts = p.outcomes().getOrDefault("TIMEOUT", 0);
        if (timeouts > p.closed() * 0.4) l.add(ur ? "- Bohat trades TIMEOUT: 10 minute mein target door ho sakte hain."
                : "- Many trades time out: targets may be too far for a 10-minute hold.");
        for (Map.Entry<String, PerformanceCalculator.Group> e : p.byStrategy().entrySet()) {
            if (e.getValue().trades() >= 10 && e.getValue().expectancyR() < 0) {
                l.add((ur ? "- Strategy " : "- Strategy ") + e.getKey() + (ur ? " ki expectancy manfi (" : " has negative expectancy (")
                        + e.getValue().expectancyR() + "R): " + (ur ? "minScore barhaein ya band karein." : "raise its minimum score or disable it."));
            }
        }
        for (Map.Entry<String, PerformanceCalculator.Group> e : p.bySession().entrySet()) {
            if (e.getValue().trades() >= 10 && e.getValue().expectancyR() < 0) {
                l.add((ur ? "- Session " : "- Session ") + e.getKey() + (ur ? " mein nuqsan: is session se bachein." : " is losing: consider avoiding it."));
            }
        }
        l.add(ur ? p.disclaimer() : p.disclaimer());
        return String.join("\n", l);
    }

    private static String pick(Text t, boolean ur) {
        return t == null ? "" : ur ? t.ur() : t.en();
    }

    private static String pct(double v) {
        return Double.isFinite(v) ? Math.round(v * 1000) / 10.0 + "%" : "n/a";
    }

    private static String round3(double v) {
        return Double.isFinite(v) ? String.valueOf(Math.round(v * 1000) / 1000.0) : "n/a";
    }
}
