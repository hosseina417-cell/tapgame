package ai.arena.nabzcrypto.model;

/** Coarse market state used to gate directional signals, not to predict returns by itself. */
public enum MarketRegime {
    TREND_UP("روند صعودی"),
    TREND_DOWN("روند نزولی"),
    RANGE("رنج / بی‌روند"),
    VOLATILE("نوسان بسیار بالا"),
    TRANSITION("گذار / نامشخص");

    public final String faLabel;

    MarketRegime(String faLabel) {
        this.faLabel = faLabel;
    }
}
