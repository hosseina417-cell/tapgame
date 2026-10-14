package ai.arena.nabzcrypto.model;

public final class SignalEvent {
    public final String recordId;
    public final String symbol;
    public final SignalState state;
    public final String messageFa;
    public final long occurredAt;

    public SignalEvent(String recordId, String symbol, SignalState state,
                       String messageFa, long occurredAt) {
        this.recordId = recordId == null ? "" : recordId;
        this.symbol = symbol == null ? "" : symbol;
        this.state = state == null ? SignalState.PENDING : state;
        this.messageFa = messageFa == null ? "" : messageFa;
        this.occurredAt = Math.max(0, occurredAt);
    }
}
