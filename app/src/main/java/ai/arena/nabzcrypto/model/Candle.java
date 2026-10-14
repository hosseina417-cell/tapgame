package ai.arena.nabzcrypto.model;

/** A completed OHLCV candle. Prices are quoted in USDT. */
public final class Candle {
    public final long openTime;
    public final long closeTime;
    public final double open;
    public final double high;
    public final double low;
    public final double close;
    public final double baseVolume;
    public final double quoteVolume;

    public Candle(long openTime, long closeTime, double open, double high, double low,
                  double close, double baseVolume, double quoteVolume) {
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.baseVolume = baseVolume;
        this.quoteVolume = quoteVolume;
    }

    public boolean isValid() {
        return openTime > 0 && closeTime > openTime
                && finitePositive(open) && finitePositive(high)
                && finitePositive(low) && finitePositive(close)
                && high >= Math.max(open, close) && low <= Math.min(open, close)
                && Double.isFinite(baseVolume) && baseVolume >= 0
                && Double.isFinite(quoteVolume) && quoteVolume >= 0;
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0;
    }
}
