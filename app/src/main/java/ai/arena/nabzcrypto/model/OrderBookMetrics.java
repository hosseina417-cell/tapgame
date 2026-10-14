package ai.arena.nabzcrypto.model;

/** Public order-book snapshot used only as a liquidity/quality gate, never as a trade trigger. */
public final class OrderBookMetrics {
    public final boolean available;
    public final double spreadPct;
    public final double bidDepthUsd;
    public final double askDepthUsd;
    public final double imbalance;
    public final double estimatedSlippagePct;

    public OrderBookMetrics(boolean available, double spreadPct, double bidDepthUsd,
                            double askDepthUsd, double imbalance,
                            double estimatedSlippagePct) {
        this.available = available;
        this.spreadPct = finiteNonNegative(spreadPct);
        this.bidDepthUsd = finiteNonNegative(bidDepthUsd);
        this.askDepthUsd = finiteNonNegative(askDepthUsd);
        this.imbalance = Double.isFinite(imbalance)
                ? Math.max(-1, Math.min(1, imbalance)) : 0;
        this.estimatedSlippagePct = finiteNonNegative(estimatedSlippagePct);
    }

    public static OrderBookMetrics unavailable() {
        return new OrderBookMetrics(false, 0, 0, 0, 0, 0);
    }

    public double minimumDepthUsd() {
        return Math.min(bidDepthUsd, askDepthUsd);
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }
}
