package ai.arena.nabzcrypto.model;

public final class ScanConfig {
    public final int marketLimit;
    public final double minimumQuoteVolume;
    public final double feePerSidePct;
    public final double riskPerTradePct;

    public ScanConfig(int marketLimit, double minimumQuoteVolume) {
        this(marketLimit, minimumQuoteVolume, 0.10, 1.0);
    }

    public ScanConfig(int marketLimit, double minimumQuoteVolume,
                      double feePerSidePct, double riskPerTradePct) {
        this.marketLimit = Math.max(20, Math.min(130, marketLimit));
        this.minimumQuoteVolume = Math.max(0, minimumQuoteVolume);
        this.feePerSidePct = Double.isFinite(feePerSidePct)
                ? Math.max(0, Math.min(1, feePerSidePct)) : 0.10;
        this.riskPerTradePct = Double.isFinite(riskPerTradePct)
                ? Math.max(0.1, Math.min(5, riskPerTradePct)) : 1.0;
    }
}
