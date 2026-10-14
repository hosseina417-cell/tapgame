package ai.arena.nabzcrypto.model;

public enum SignalState {
    PENDING("در انتظار تأیید", false),
    CONFIRMED("تأییدشده", false),
    TARGET_1_HIT("هدف ۱ لمس شد", false),
    TARGET_2_HIT("هدف ۲ لمس شد", true),
    INVALIDATED("باطل شد", true),
    TP1_THEN_INVALIDATED("هدف ۱، سپس ابطال", true),
    EXPIRED("منقضی شد", true),
    EXPIRED_AFTER_TP1("هدف ۱، سپس انقضا", true);

    public final String faLabel;
    public final boolean terminal;

    SignalState(String faLabel, boolean terminal) {
        this.faLabel = faLabel;
        this.terminal = terminal;
    }
}
