package ai.arena.nabzcrypto.ui;

import ai.arena.nabzcrypto.model.ScanResult;

import java.math.BigDecimal;
import java.util.Locale;

/** Builds a static, auditable TradingView overlay for one completed NabzCrypto scan. */
public final class PineScriptBuilder {
    private PineScriptBuilder() {}

    public static String build(ScanResult result) {
        if (result == null) return "";
        boolean bearish = result.objective1 < result.referencePrice;
        String side = result.signal.isBuy() ? "BUY SETUP"
                : result.signal.isSell() ? "SELL SETUP" : "WAIT - NO ENTRY SIGNAL";
        return build(result.ticker.baseAsset + "USDT", bearish, side,
                result.referencePrice, result.zoneLow, result.zoneHigh,
                result.invalidation, result.objective1, result.objective2,
                result.candleClosedAt);
    }

    public static String build(String symbol, boolean bearish, double reference,
                               double entryLow, double entryHigh, double invalidation,
                               double target1, double target2, long generatedAt) {
        return build(symbol, bearish, bearish ? "DOWN SCENARIO" : "UP SCENARIO",
                reference, entryLow, entryHigh, invalidation, target1, target2, generatedAt);
    }

    private static String build(String symbol, boolean bearish, String side, double reference,
                                double entryLow, double entryHigh, double invalidation,
                                double target1, double target2, long generatedAt) {
        String safeSymbol = sanitizeSymbol(symbol);
        String color = side.startsWith("WAIT") ? "color.orange"
                : bearish ? "color.red" : "color.lime";
        return "//@version=5\n"
                + "indicator(\"NabzCrypto " + safeSymbol
                + " Trade Map\", overlay=true, max_labels_count=10)\n\n"
                + "// Static levels from the closed-candle scan at " + Math.max(0, generatedAt)
                + ". Generate a new script after a new scan.\n"
                + "entryLow = input.float(" + number(entryLow) + ", \"Entry zone low\")\n"
                + "entryHigh = input.float(" + number(entryHigh) + ", \"Entry zone high\")\n"
                + "invalidation = input.float(" + number(invalidation) + ", \"Invalidation / stop\")\n"
                + "target1 = input.float(" + number(target1) + ", \"Statistical target 1\")\n"
                + "target2 = input.float(" + number(target2) + ", \"Statistical target 2\")\n"
                + "referencePrice = input.float(" + number(reference) + ", \"Scan reference\")\n\n"
                + "entryLowPlot = plot(entryLow, \"Entry low\", color=color.aqua, linewidth=2, style=plot.style_linebr)\n"
                + "entryHighPlot = plot(entryHigh, \"Entry high\", color=color.aqua, linewidth=2, style=plot.style_linebr)\n"
                + "fill(entryLowPlot, entryHighPlot, color=color.new(color.aqua, 88), title=\"Entry zone\")\n"
                + "plot(invalidation, \"Invalidation\", color=color.red, linewidth=2, style=plot.style_linebr)\n"
                + "plot(target1, \"Target 1\", color=color.lime, linewidth=2, style=plot.style_linebr)\n"
                + "plot(target2, \"Target 2\", color=color.green, linewidth=2, style=plot.style_linebr)\n"
                + "plot(referencePrice, \"Scan reference\", color=color.new(color.white, 45), linewidth=1, style=plot.style_linebr)\n\n"
                + "planColor = " + color + "\n"
                + "var label planLabel = na\n"
                + "if barstate.islast\n"
                + "    label.delete(planLabel)\n"
                + "    planLabel := label.new(bar_index, referencePrice, \"NABZ "
                + safeSymbol + " - " + side
                + "\\nSTATIC ATR LEVELS - NOT FINANCIAL ADVICE\", style=label.style_label_left, color=color.new(planColor, 12), textcolor=color.white)\n";
    }

    public static String summary(ScanResult result) {
        if (result == null) return "";
        String side = result.signal.isBuy() ? "سناریوی خرید"
                : result.signal.isSell() ? "سناریوی فروش"
                : "بدون سیگنال ورود؛ فقط سطوح پایش";
        return String.format(Locale.US,
                "%s • %s\nناحیه بررسی: %s تا %s\nابطال تحلیل: %s\nهدف آماری ۱: %s\nهدف آماری ۲: %s\nکندل مبنا: %d\nاین سطوح سفارش یا تضمین سود نیستند.",
                result.ticker.displaySymbol(), side,
                Formatters.compactPrice(result.zoneLow),
                Formatters.compactPrice(result.zoneHigh),
                Formatters.compactPrice(result.invalidation),
                Formatters.compactPrice(result.objective1),
                Formatters.compactPrice(result.objective2),
                result.candleClosedAt);
    }

    private static String sanitizeSymbol(String symbol) {
        String value = symbol == null ? "SYMBOL" : symbol.toUpperCase(Locale.US)
                .replaceAll("[^A-Z0-9]", "");
        return value.isEmpty() ? "SYMBOL" : value;
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) return "0.0";
        String plain = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return plain.indexOf('.') >= 0 ? plain : plain + ".0";
    }
}
