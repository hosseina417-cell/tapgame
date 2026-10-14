package ai.arena.nabzcrypto.model;

/**
 * A normalized USDT spot ticker. A ticker may represent one exchange or a robust
 * cross-exchange consensus. Volume is quote volume in USDT.
 */
public final class MarketTicker {
    public final String symbol;
    public final String apiSymbol;
    public final String baseAsset;
    public final String quoteAsset;
    public final double lastPrice;
    public final double change24hPct;
    public final double quoteVolume24h;
    public final long receivedAt;
    public final int sourceCount;
    public final double priceSpreadPct;
    public final int marketCapRank;
    public final double marketCapUsd;

    public MarketTicker(String symbol, String apiSymbol, String baseAsset, String quoteAsset,
                        double lastPrice, double change24hPct, double quoteVolume24h,
                        long receivedAt) {
        this(symbol, apiSymbol, baseAsset, quoteAsset, lastPrice, change24hPct,
                quoteVolume24h, receivedAt, 1, 0, 0, 0);
    }

    public MarketTicker(String symbol, String apiSymbol, String baseAsset, String quoteAsset,
                        double lastPrice, double change24hPct, double quoteVolume24h,
                        long receivedAt, int sourceCount, double priceSpreadPct,
                        int marketCapRank, double marketCapUsd) {
        this.symbol = symbol;
        this.apiSymbol = apiSymbol;
        this.baseAsset = baseAsset;
        this.quoteAsset = quoteAsset;
        this.lastPrice = lastPrice;
        this.change24hPct = change24hPct;
        this.quoteVolume24h = quoteVolume24h;
        this.receivedAt = receivedAt;
        this.sourceCount = Math.max(1, sourceCount);
        this.priceSpreadPct = Double.isFinite(priceSpreadPct) ? Math.max(0, priceSpreadPct) : 0;
        this.marketCapRank = Math.max(0, marketCapRank);
        this.marketCapUsd = Double.isFinite(marketCapUsd) ? Math.max(0, marketCapUsd) : 0;
    }

    public String displaySymbol() {
        return baseAsset + "/" + quoteAsset;
    }

    public boolean isValid() {
        return symbol != null && !symbol.isEmpty() && apiSymbol != null && !apiSymbol.isEmpty()
                && baseAsset != null && !baseAsset.isEmpty()
                && "USDT".equals(quoteAsset)
                && Double.isFinite(lastPrice) && lastPrice > 0
                && Double.isFinite(change24hPct)
                && Double.isFinite(quoteVolume24h) && quoteVolume24h >= 0;
    }
}
