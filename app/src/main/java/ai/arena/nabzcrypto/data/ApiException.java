package ai.arena.nabzcrypto.data;

public final class ApiException extends Exception {
    public final int statusCode;

    public ApiException(String message) {
        this(message, 0);
    }

    public ApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }
}
