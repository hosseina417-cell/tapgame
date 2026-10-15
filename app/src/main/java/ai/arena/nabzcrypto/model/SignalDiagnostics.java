package ai.arena.nabzcrypto.model;

/** Transparent gates behind normal/strong labels. */
public final class SignalDiagnostics {
    public final int oneHourTrend;
    public final int fourHourTrend;
    public final int benchmarkTrend;
    public final boolean higherTimeframeAligned;
    public final OrderBookMetrics orderBook;
    public final double riskReward1;
    public final double riskReward2;
    public final double estimatedRoundTripCostPct;
    public final double positionSizePer1000;
    public final boolean riskRewardPassed;
    public final boolean liquidityPassed;
    public final int historyDays;
    public final boolean qualityStrong;
    public final boolean technicalStrong;
    public final boolean regimeStrong;
    public final boolean independentConfirmation;
    public final boolean entryTimingPassed;
    public final boolean strongEligible;

    public SignalDiagnostics(int oneHourTrend, int fourHourTrend, int benchmarkTrend,
                             boolean higherTimeframeAligned, OrderBookMetrics orderBook,
                             double riskReward1, double riskReward2,
                             double estimatedRoundTripCostPct, double positionSizePer1000,
                             boolean riskRewardPassed, boolean liquidityPassed,
                             int historyDays, boolean qualityStrong,
                             boolean technicalStrong, boolean regimeStrong,
                             boolean independentConfirmation, boolean strongEligible) {
        this(oneHourTrend, fourHourTrend, benchmarkTrend, higherTimeframeAligned,
                orderBook, riskReward1, riskReward2, estimatedRoundTripCostPct,
                positionSizePer1000, riskRewardPassed, liquidityPassed, historyDays,
                qualityStrong, technicalStrong, regimeStrong, independentConfirmation,
                true, strongEligible);
    }

    public SignalDiagnostics(int oneHourTrend, int fourHourTrend, int benchmarkTrend,
                             boolean higherTimeframeAligned, OrderBookMetrics orderBook,
                             double riskReward1, double riskReward2,
                             double estimatedRoundTripCostPct, double positionSizePer1000,
                             boolean riskRewardPassed, boolean liquidityPassed,
                             int historyDays, boolean qualityStrong,
                             boolean technicalStrong, boolean regimeStrong,
                             boolean independentConfirmation, boolean entryTimingPassed,
                             boolean strongEligible) {
        this.oneHourTrend = clampTrend(oneHourTrend);
        this.fourHourTrend = clampTrend(fourHourTrend);
        this.benchmarkTrend = clampTrend(benchmarkTrend);
        this.higherTimeframeAligned = higherTimeframeAligned;
        this.orderBook = orderBook == null ? OrderBookMetrics.unavailable() : orderBook;
        this.riskReward1 = finiteNonNegative(riskReward1);
        this.riskReward2 = finiteNonNegative(riskReward2);
        this.estimatedRoundTripCostPct = finiteNonNegative(estimatedRoundTripCostPct);
        this.positionSizePer1000 = Math.min(1_000, finiteNonNegative(positionSizePer1000));
        this.riskRewardPassed = riskRewardPassed;
        this.liquidityPassed = liquidityPassed;
        this.historyDays = Math.max(0, Math.min(365, historyDays));
        this.qualityStrong = qualityStrong;
        this.technicalStrong = technicalStrong;
        this.regimeStrong = regimeStrong;
        this.independentConfirmation = independentConfirmation;
        this.entryTimingPassed = entryTimingPassed;
        this.strongEligible = strongEligible;
    }

    public static SignalDiagnostics unavailable() {
        return new SignalDiagnostics(0, 0, 0, false, OrderBookMetrics.unavailable(),
                0, 0, 0.20, 0, false, true, 0,
                false, false, false, false, false, false);
    }

    private static int clampTrend(int value) {
        return Math.max(-100, Math.min(100, value));
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }
}
