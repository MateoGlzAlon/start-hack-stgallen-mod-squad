package com.leash.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Exchange rates to CHF, kept in memory. Nothing is hardcoded: {@link FxRates} fills this at startup from Viseca's reference data
 * or from the data pack's fx_rates.csv. Until then (or if both fail) only CHF is known, and an amount in another currency can't be
 * converted, which the engine treats as unknown, never as permission.
 */
public final class Fx {
    private static volatile Map<String, BigDecimal> toChf = Map.of("CHF", BigDecimal.ONE);
    private static volatile String source = "none loaded";

    private Fx() {}

    static void load(Map<String, BigDecimal> rates, String from) {
        Map<String, BigDecimal> m = new TreeMap<>();
        rates.forEach((c, r) -> m.put(c, r.stripTrailingZeros()));
        m.put("CHF", BigDecimal.ONE);
        toChf = Map.copyOf(m);
        source = from;
    }

    /** What 1 unit of the currency is worth in CHF. Null for a currency without a rate. */
    public static BigDecimal rate(String currency) {
        return currency == null ? null : toChf.get(currency.toUpperCase(Locale.ROOT));
    }

    /** amount x rate, to the cent like Viseca's billing_amount_chf. Null for a currency without a rate. */
    public static BigDecimal toChf(BigDecimal amount, String currency) {
        BigDecimal r = rate(currency);
        return r == null ? null : amount.multiply(r).setScale(2, RoundingMode.HALF_UP);
    }

    public static Map<String, BigDecimal> rates() { return new TreeMap<>(toChf); }

    public static String source() { return source; }

    /** "EUR 0.95, GBP 1.12, USD 0.87" (CHF left out), for prompts and messages. */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(toChf).forEach((c, r) -> {
            if ("CHF".equals(c)) return;
            if (sb.length() > 0) sb.append(", ");
            sb.append(c).append(' ').append(r.stripTrailingZeros().toPlainString());
        });
        return sb.length() == 0 ? "no rates loaded" : sb.toString();
    }
}
