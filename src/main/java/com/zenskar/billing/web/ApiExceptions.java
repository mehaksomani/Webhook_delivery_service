package com.zenskar.billing.web;

import java.time.Instant;

/**
 * The small set of typed exceptions and the error payload shape used at the
 * inbound REST surface. Nested here because the project has few of them and
 * splitting them into one-class-per-file was noise.
 */
public final class ApiExceptions {

    private ApiExceptions() {}

    public record ErrorResponse(Instant timestamp, int status, String error, String message, String path) {}

    public static class EndpointNotFoundException extends RuntimeException {
        public EndpointNotFoundException(String message) {
            super(message);
        }
    }

    public static class InvalidSubmissionException extends RuntimeException {
        public InvalidSubmissionException(String message) {
            super(message);
        }
    }
}
