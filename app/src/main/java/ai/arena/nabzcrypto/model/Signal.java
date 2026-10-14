package ai.arena.nabzcrypto.model;

public enum Signal {
    STRONG_BUY("خرید قوی", "تأیید چندگانهٔ صعودی"),
    BUY("خرید", "برتری شواهد صعودی"),
    NEUTRAL("صبر", "شواهد کافی نیست"),
    SELL("فروش", "برتری شواهد نزولی"),
    STRONG_SELL("فروش قوی", "تأیید چندگانهٔ نزولی"),
    PUMP_ALERT("هشدار جهش", "احتمال حرکت پامپی؛ تعقیب نکنید"),
    DUMP_ALERT("هشدار ریزش", "احتمال حرکت دامپی؛ مدیریت ریسک"),
    STALE("داده نامعتبر", "نیازمند به‌روزرسانی");

    public final String faLabel;
    public final String faDescription;

    Signal(String faLabel, String faDescription) {
        this.faLabel = faLabel;
        this.faDescription = faDescription;
    }

    public boolean isBuy() {
        return this == BUY || this == STRONG_BUY;
    }

    public boolean isSell() {
        return this == SELL || this == STRONG_SELL;
    }

    public boolean isMovementAlert() {
        return this == PUMP_ALERT || this == DUMP_ALERT;
    }
}
