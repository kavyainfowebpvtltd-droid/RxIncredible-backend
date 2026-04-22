package com.rxincredible.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

public final class CurrencyUtil {

    private static final Locale INDIA_LOCALE = new Locale("en", "IN");
    private static final Locale US_LOCALE = Locale.US;

    private CurrencyUtil() {
    }

    public static boolean isIndia(String country) {
        if (country == null || country.isBlank()) {
            return true;
        }

        String normalized = country.trim().toLowerCase(Locale.ENGLISH);
        return "india".equals(normalized) || "in".equals(normalized) || "bharat".equals(normalized);
    }

    public static String resolveCurrencyCode(String country) {
        return isIndia(country) ? "INR" : "USD";
    }

    public static String resolveCurrencySymbol(String country) {
        return isIndia(country) ? "Rs." : "$";
    }

    public static String formatAmount(BigDecimal amount, String country) {
        NumberFormat formatter = NumberFormat.getCurrencyInstance(isIndia(country) ? INDIA_LOCALE : US_LOCALE);
        BigDecimal safeAmount = amount != null ? amount : BigDecimal.ZERO;
        return formatter.format(safeAmount.setScale(2, RoundingMode.HALF_UP));
    }
}
