package ai.arena.nabzcrypto.ui;

import ai.arena.nabzcrypto.model.Signal;

public final class SignalColors {
    private SignalColors() {}

    public static int color(Signal signal) {
        switch (signal) {
            case STRONG_BUY:
            case BUY:
                return Theme.GREEN;
            case STRONG_SELL:
            case SELL:
                return Theme.RED;
            case PUMP_ALERT:
            case DUMP_ALERT:
                return Theme.AMBER;
            case STALE:
                return Theme.MUTED;
            default:
                return Theme.CYAN;
        }
    }
}
