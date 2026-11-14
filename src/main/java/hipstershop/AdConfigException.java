package hipstershop;

/** The ad configuration is missing, unreadable or invalid. The service refuses to start on it. */
final class AdConfigException extends RuntimeException {

    AdConfigException(String message) {
        super(message);
    }

    AdConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
