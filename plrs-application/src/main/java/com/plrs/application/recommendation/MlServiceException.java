package com.plrs.application.recommendation;

/**
 * Thrown by {@link MlServiceClient} when the upstream ML service is
 * unreachable, returns a 5xx, or sends an unparseable payload.
 *
 * <p>Callers in the {@code recommendation} package treat this as the
 * NFR-11 fallback signal — switch to the in-process Java
 * implementation rather than fail the request.
 */
public class MlServiceException extends RuntimeException {

    public MlServiceException(String message) {
        super(message);
    }

    public MlServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
