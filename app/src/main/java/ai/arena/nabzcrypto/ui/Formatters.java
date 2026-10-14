package ai.arena.nabzcrypto.ui;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Formatters {
    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.US);

    private Formatters() {}

    public static String price(double value) {
        if (!Double.isFinite(value)) return "—";
        double abs = Math.abs(value);
        if (abs >= 1000) return format("#,##0.00", value);
        if (abs >= 1) return format("0.0000", value);
        if (abs >= 0.01) return format("0.000000", value);
        return format("0.00000000", value);
    }

    public static String compactPrice(double value) {
        if (!Double.isFinite(value)) return "—";
        double abs = Math.abs(value);
        if (abs >= 1000) return format("#,##0.##", value);
        if (abs >= 1) return format("0.####", value);
        if (abs >= 0.01) return format("0.######", value);
        return format("0.########", value);
    }

    public static String directionalScore(int score) {
        int bounded = Math.max(-100, Math.min(100, score));
        if (bounded > 0) return "قدرت صعودی " + bounded + "/100";
        if (bounded < 0) return "قدرت نزولی " + Math.abs(bounded) + "/100";
        return "قدرت جهت‌دار 0/100";
    }

    public static String signedPercent(double value) {
        if (!Double.isFinite(value)) return "—";
        return String.format(Locale.US, "%+.2f%%", value);
    }

    public static String volume(double value) {
        if (!Double.isFinite(value)) return "—";
        if (value >= 1_000_000_000) return String.format(Locale.US, "$%.1fB", value / 1_000_000_000.0);
        if (value >= 1_000_000) return String.format(Locale.US, "$%.1fM", value / 1_000_000.0);
        if (value >= 1_000) return String.format(Locale.US, "$%.1fK", value / 1_000.0);
        return String.format(Locale.US, "$%.0f", value);
    }

    public static String dateTime(long timestamp) {
        if (timestamp <= 0) return "نامشخص";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date(timestamp));
    }

    public static String age(long timestamp) {
        if (timestamp <= 0) return "نامشخص";
        long seconds = Math.max(0, (System.currentTimeMillis() - timestamp) / 1000L);
        if (seconds < 60) return seconds + " ثانیه پیش";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " دقیقه پیش";
        long hours = minutes / 60;
        if (hours < 24) return hours + " ساعت پیش";
        return (hours / 24) + " روز پیش";
    }

    private static String format(String pattern, double value) {
        return new DecimalFormat(pattern, SYMBOLS).format(value);
    }
}
