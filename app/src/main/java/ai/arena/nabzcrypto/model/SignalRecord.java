package ai.arena.nabzcrypto.model;

/** Persisted paper-only lifecycle record. It never places or recommends an order. */
public final class SignalRecord {
    public final String id;
    public final String symbol;
    public final String baseAsset;
    public final int direction;
    public final boolean strong;
    public final long issuedAt;
    public long confirmedAt;
    public long lastEvaluatedAt;
    public long closedAt;
    public final double entryPrice;
    public final double invalidation;
    public final double target1;
    public final double target2;
    public final MarketRegime regime;
    public final int score;
    public final int dataQuality;
    public final double estimatedFeePct;
    public final double estimatedSlippagePct;
    public final double estimatedCostPct;
    public SignalState state;
    public long target1HitAt;
    public double exitPrice;
    public double netReturnPct;
    public double outcomeR;

    public SignalRecord(String id, String symbol, String baseAsset, int direction,
                        boolean strong, long issuedAt, long lastEvaluatedAt, long closedAt,
                        double entryPrice, double invalidation, double target1, double target2,
                        MarketRegime regime, int score, int dataQuality,
                        double estimatedCostPct, SignalState state, long target1HitAt,
                        double exitPrice, double netReturnPct, double outcomeR) {
        this(id, symbol, baseAsset, direction, strong, issuedAt, lastEvaluatedAt, closedAt,
                entryPrice, invalidation, target1, target2, regime, score, dataQuality,
                estimatedCostPct, 0, state, target1HitAt, exitPrice, netReturnPct, outcomeR);
    }

    public SignalRecord(String id, String symbol, String baseAsset, int direction,
                        boolean strong, long issuedAt, long lastEvaluatedAt, long closedAt,
                        double entryPrice, double invalidation, double target1, double target2,
                        MarketRegime regime, int score, int dataQuality,
                        double estimatedFeePct, double estimatedSlippagePct,
                        SignalState state, long target1HitAt,
                        double exitPrice, double netReturnPct, double outcomeR) {
        this.id = id == null ? "" : id;
        this.symbol = symbol == null ? "" : symbol;
        this.baseAsset = baseAsset == null ? "" : baseAsset;
        this.direction = direction >= 0 ? 1 : -1;
        this.strong = strong;
        this.issuedAt = Math.max(0, issuedAt);
        this.lastEvaluatedAt = Math.max(this.issuedAt, lastEvaluatedAt);
        this.closedAt = Math.max(0, closedAt);
        this.entryPrice = finitePositive(entryPrice);
        this.invalidation = finitePositive(invalidation);
        this.target1 = finitePositive(target1);
        this.target2 = finitePositive(target2);
        this.regime = regime == null ? MarketRegime.TRANSITION : regime;
        this.score = Math.max(-100, Math.min(100, score));
        this.dataQuality = Math.max(0, Math.min(100, dataQuality));
        this.estimatedFeePct = finiteNonNegative(estimatedFeePct);
        this.estimatedSlippagePct = finiteNonNegative(estimatedSlippagePct);
        this.estimatedCostPct = this.estimatedFeePct + this.estimatedSlippagePct;
        this.state = state == null ? SignalState.PENDING : state;
        this.confirmedAt = strong || this.state == SignalState.CONFIRMED
                || this.state == SignalState.TARGET_1_HIT
                || this.state == SignalState.TARGET_2_HIT
                || this.state == SignalState.TP1_THEN_INVALIDATED
                || this.state == SignalState.EXPIRED_AFTER_TP1 ? this.issuedAt : 0;
        this.target1HitAt = Math.max(0, target1HitAt);
        this.exitPrice = finiteNonNegative(exitPrice);
        this.netReturnPct = Double.isFinite(netReturnPct) ? netReturnPct : 0;
        this.outcomeR = Double.isFinite(outcomeR) ? outcomeR : 0;
    }

    public boolean active() {
        return !state.terminal;
    }

    public String directionFa() {
        return direction > 0 ? "صعودی" : "نزولی";
    }

    public SignalRecord copy() {
        SignalRecord result = new SignalRecord(id, symbol, baseAsset, direction, strong, issuedAt,
                lastEvaluatedAt, closedAt, entryPrice, invalidation, target1, target2,
                regime, score, dataQuality, estimatedFeePct, estimatedSlippagePct,
                state, target1HitAt, exitPrice, netReturnPct, outcomeR);
        result.confirmedAt = confirmedAt;
        return result;
    }

    private static double finitePositive(double value) {
        return Double.isFinite(value) && value > 0 ? value : 0;
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }
}
