package ai.arena.nabzcrypto.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded immutable OHLC window for the interactive local chart. */
public final class TradeChartData {
    public final long[] openTimes;
    public final double[] opens;
    public final double[] highs;
    public final double[] lows;
    public final double[] closes;

    public TradeChartData(long[] openTimes, double[] opens, double[] highs,
                          double[] lows, double[] closes) {
        int count = minimumLength(openTimes, opens, highs, lows, closes);
        this.openTimes = copy(openTimes, count);
        this.opens = copy(opens, count);
        this.highs = copy(highs, count);
        this.lows = copy(lows, count);
        this.closes = copy(closes, count);
    }

    public static TradeChartData fromCandles(List<Candle> candles, int maximum) {
        if (candles == null || candles.isEmpty() || maximum <= 0) return empty();
        List<Candle> valid = new ArrayList<>();
        for (Candle candle : candles) if (candle != null && candle.isValid()) valid.add(candle);
        Collections.sort(valid, (a, b) -> Long.compare(a.openTime, b.openTime));
        int count = Math.min(maximum, valid.size());
        int start = valid.size() - count;
        long[] times = new long[count];
        double[] opens = new double[count];
        double[] highs = new double[count];
        double[] lows = new double[count];
        double[] closes = new double[count];
        for (int i = 0; i < count; i++) {
            Candle candle = valid.get(start + i);
            times[i] = candle.openTime;
            opens[i] = candle.open;
            highs[i] = candle.high;
            lows[i] = candle.low;
            closes[i] = candle.close;
        }
        return new TradeChartData(times, opens, highs, lows, closes);
    }

    public static TradeChartData empty() {
        return new TradeChartData(new long[0], new double[0], new double[0],
                new double[0], new double[0]);
    }

    public int size() {
        return closes.length;
    }

    private static int minimumLength(long[] times, double[] opens, double[] highs,
                                     double[] lows, double[] closes) {
        if (times == null || opens == null || highs == null || lows == null || closes == null) return 0;
        return Math.min(times.length, Math.min(opens.length,
                Math.min(highs.length, Math.min(lows.length, closes.length))));
    }

    private static long[] copy(long[] source, int count) {
        long[] result = new long[count];
        if (count > 0) System.arraycopy(source, 0, result, 0, count);
        return result;
    }

    private static double[] copy(double[] source, int count) {
        double[] result = new double[count];
        if (count > 0) System.arraycopy(source, 0, result, 0, count);
        return result;
    }
}
