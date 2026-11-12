package com.aurumxau.strategy;

import com.aurumxau.model.Direction;
import com.aurumxau.model.Text;
import com.aurumxau.util.Prices;

/**
 * Entry / stop / target / cost arithmetic shared by all strategies, live paper trading and backtests.
 * BUY enters at ask and exits at bid; SELL enters at bid and exits at ask.
 */
public final class SetupMath {
    private SetupMath() {}

    public record Levels(double entryLow, double entryHigh, double entryRef, double stop, double tp1, double tp2,
                         double risk, double netRr1, double netRr2, double blended, Text rejection) {
        public boolean ok() { return rejection == null; }
    }

    /**
     * @param entryRef       expected fill (ask for LONG, bid for SHORT) before slippage
     * @param structuralStop exit-side stop level beyond the invalidating structure (buffer already applied)
     * @param opposing       nearest opposing liquidity / zone edge in the trade direction (NaN if none known)
     */
    public static Levels compute(Direction d, double entryRef, double structuralStop, double atrM1, double spread,
                                 double opposing, StrategySettings s, int precision) {
        int sg = d.sign();
        double risk = (entryRef - structuralStop) * sg;
        double minRisk = Math.max(Math.max(0.5 * atrM1, 2 * spread), 0.30);
        double maxRisk = 3.0 * atrM1;
        if (!(risk > 0)) return reject("Stop is on the wrong side of entry", "Stop entry ki galat taraf hai");
        if (risk < minRisk) {
            // Widen to the minimum noise distance rather than placing a stop inside the spread/noise.
            risk = minRisk;
            structuralStop = entryRef - sg * risk;
        }
        if (risk > maxRisk) return reject("Stop too wide for a 10-minute scalp (" + Prices.r2(risk) + " > 3x M1 ATR)",
                "10 minute scalp ke liye stop bohat door hai (" + Prices.r2(risk) + " > 3x M1 ATR)");
        double tp1 = entryRef + sg * risk;
        double tp2 = entryRef + sg * 2.0 * risk;
        double reach = 4.5 * atrM1;
        if ((tp2 - entryRef) * sg > reach) tp2 = entryRef + sg * reach;
        if (Double.isFinite(opposing) && (opposing - entryRef) * sg > 0) {
            double capped = opposing - sg * 0.1 * atrM1;
            if ((capped - entryRef) * sg < (tp2 - entryRef) * sg) tp2 = capped;
        }
        if ((tp2 - entryRef) * sg < 1.3 * risk) {
            return reject("Not enough room: opposing liquidity/zone or 10-min reach limits target below 1.3R",
                    "Target ke liye jagah kam hai: saamne liquidity/zone ya 10 minute ki had 1.3R se kam deti hai");
        }
        double slip = s.slippage(), comm = s.commissionPerOz();
        double netRisk = risk + 2 * slip + comm;
        double r1 = ((tp1 - entryRef) * sg - slip - comm) / netRisk;
        double r2 = ((tp2 - entryRef) * sg - slip - comm) / netRisk;
        double w = s.tp1ClosePercent() / 100.0;
        double blended = w * r1 + (1 - w) * r2;
        double zoneAgainst = 0.10 * atrM1, zoneWith = 0.25 * atrM1;
        double lo = d == Direction.LONG ? entryRef - zoneWith : entryRef - zoneAgainst;
        double hi = d == Direction.LONG ? entryRef + zoneAgainst : entryRef + zoneWith;
        Text rej = null;
        if (r2 < s.minNetRR()) {
            rej = Text.of("RR_LOW", "Net reward/risk to TP2 " + Prices.r2(r2) + " is below minimum " + s.minNetRR(),
                    "TP2 tak net reward/risk " + Prices.r2(r2) + " hai, kam az kam " + s.minNetRR() + " chahiye");
        }
        return new Levels(Prices.round(lo, precision), Prices.round(hi, precision), Prices.round(entryRef, precision),
                Prices.round(structuralStop, precision), Prices.round(tp1, precision), Prices.round(tp2, precision),
                Prices.round(risk, precision), Prices.r2(r1), Prices.r2(r2), Prices.r2(blended), rej);
    }

    private static Levels reject(String en, String ur) {
        return new Levels(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Text.of("LEVELS", en, ur));
    }

    /** Position size from account risk. Lots are rounded DOWN to the broker lot step. */
    public static TradeSetup.PositionSize size(double risk, StrategySettings s) {
        double netRisk = risk + 2 * s.slippage() + s.commissionPerOz();
        double riskUsd = s.accountEquity() * s.riskPercent() / 100.0;
        double oz = netRisk > 0 ? riskUsd / netRisk : 0;
        double lots = Math.floor(oz / s.contractSizeOz() / s.lotStep() + 1e-9) * s.lotStep();
        boolean below = lots < s.minLot();
        Text note = below
                ? Text.of("SIZE_MIN", "Risk budget is below the broker minimum lot; reduce stop or skip",
                "Risk budget broker ke minimum lot se kam hai; stop chhota karein ya trade chhor dein")
                : Text.of("SIZE_OK", "Size assumes " + s.contractSizeOz() + " oz per lot. Verify with your broker.",
                "Size " + s.contractSizeOz() + " oz fi lot maan kar hai. Apne broker se tasdeeq karein.");
        return new TradeSetup.PositionSize(Prices.r2(riskUsd), Prices.r2(oz), below ? 0 : Prices.round(lots, 3), below, note);
    }
}
