package ai.arena.nabzcrypto.model;

/** A normalized USDT spot ticker supplied by one public exchange API. */
public final class MarketTicker {
    public final String symbol;
    public final String apiSymbol;
    public final String baseAsset;
    public final String quoteAsset;
    public final double lastPrice;
    public final double change24hPct;
    public final double quoteVolume24h;
    public final long receivedAt;

    public MarketTicker(String symbol, String apiSymbol, String baseAsset, String quoteAsset,
                        double lastPrice, double change24hPct, double quoteVolume24h,
                        long receivedAt) {
        this.symbol = symbol;
        this.apiSymbol = apiSymbol;
        this.baseAsset = baseAsset;
        this.quoteAsset = quoteAsset;
        this.lastPrice = lastPrice;
        this.change24hPct = change24hPct;
        this.quoteVolume24h = quoteVolume24h;
        this.receivedAt = receivedAt;
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
