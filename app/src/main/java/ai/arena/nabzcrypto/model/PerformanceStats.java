package ai.arena.nabzcrypto.model;

public final class PerformanceStats {
    public final int totalSignals;
    public final int activeSignals;
    public final int completedSignals;
    public final int positiveOutcomes;
    public final int invalidatedOutcomes;
    public final double positiveRatePct;
    public final double averageR;
    public final double averageNetReturnPct;

    public static PerformanceStats empty() {
        return new PerformanceStats(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public PerformanceStats(int totalSignals, int activeSignals, int completedSignals,
                            int positiveOutcomes, int invalidatedOutcomes,
                            double positiveRatePct, double averageR,
                            double averageNetReturnPct) {
        this.totalSignals = Math.max(0, totalSignals);
        this.activeSignals = Math.max(0, activeSignals);
        this.completedSignals = Math.max(0, completedSignals);
        this.positiveOutcomes = Math.max(0, positiveOutcomes);
        this.invalidatedOutcomes = Math.max(0, invalidatedOutcomes);
        this.positiveRatePct = Double.isFinite(positiveRatePct)
                ? Math.max(0, Math.min(100, positiveRatePct)) : 0;
        this.averageR = Double.isFinite(averageR) ? averageR : 0;
        this.averageNetReturnPct = Double.isFinite(averageNetReturnPct)
                ? averageNetReturnPct : 0;
    }
}
