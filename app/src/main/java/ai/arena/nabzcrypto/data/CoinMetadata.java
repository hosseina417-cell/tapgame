package ai.arena.nabzcrypto.data;

final class CoinMetadata {
    final int marketCapRank;
    final double marketCapUsd;

    CoinMetadata(int marketCapRank, double marketCapUsd) {
        this.marketCapRank = Math.max(0, marketCapRank);
        this.marketCapUsd = Double.isFinite(marketCapUsd) ? Math.max(0, marketCapUsd) : 0;
    }
}
