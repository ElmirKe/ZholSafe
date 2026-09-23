package kz.zholsafe.network;

/** Explicit network outcome; failures are values and never enter the local risk path. */
public record ClientResult<T>(boolean successful, T value, int httpStatus, String error) {
    public static <T> ClientResult<T> success(T value, int status) {
        return new ClientResult<>(true, value, status, null);
    }
    public static <T> ClientResult<T> failure(int status, String error) {
        return new ClientResult<>(false, null, status, error == null ? "network failure" : error);
    }
}
