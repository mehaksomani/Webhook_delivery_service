package com.zenskar.billing.domain;

/**
 * Classification of an HTTP attempt's result. Drives retry vs dead-letter
 * routing.
 * <p>
 * SUCCESS: 2xx response.
 * RETRIABLE_FAILURE: 5xx, 408 Request Timeout, 429 Too Many Requests,
 * connect/IO error, read timeout.
 * PERMANENT_FAILURE: 4xx (other than 408/429) — receiver said the request
 * itself is bad; retrying won't help.
 * CRASH: the worker process died mid-attempt. The receiver may or may not have
 * processed the request.
 */
public enum AttemptOutcome {
    SUCCESS,
    RETRIABLE_FAILURE,
    PERMANENT_FAILURE,
    CRASH;

    public boolean isFailure() {
        return this != SUCCESS;
    }

    /** Map an HTTP status code to an outcome. */
    public static AttemptOutcome fromStatus(int status) {
        if (status >= 200 && status < 300) {
            return SUCCESS;
        }
        if (status == 408 || status == 429) {
            return RETRIABLE_FAILURE;
        }
        if (status >= 400 && status < 500) {
            return PERMANENT_FAILURE;
        }
        return RETRIABLE_FAILURE;
    }
}
