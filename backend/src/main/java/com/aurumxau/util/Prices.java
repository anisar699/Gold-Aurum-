package com.aurumxau.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Price rounding helpers. Prices are kept at the provider's supported precision only. */
public final class Prices {
    private Prices() {}

    public static double round(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    /** Rounds a derived (non-price) value for display, e.g. ATR or a score. */
    public static double r3(double v) {
        return round(v, 3);
    }

    public static double r2(double v) {
        return round(v, 2);
    }

    public static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
