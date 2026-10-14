package ai.arena.nabzcrypto.model;

public final class ScanConfig {
    public final int marketLimit;
    public final double minimumQuoteVolume;

    public ScanConfig(int marketLimit, double minimumQuoteVolume) {
        this.marketLimit = Math.max(20, Math.min(130, marketLimit));
        this.minimumQuoteVolume = Math.max(0, minimumQuoteVolume);
    }
}
