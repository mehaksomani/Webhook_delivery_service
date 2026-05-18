package com.zenskar.billing.web;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import com.zenskar.billing.web.ApiExceptions.EndpointNotFoundException;
import com.zenskar.billing.web.ApiExceptions.ErrorResponse;
import com.zenskar.billing.web.ApiExceptions.InvalidSubmissionException;

import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(EndpointNotFoundException.class)
    public ResponseEntity<ErrorResponse> onEndpointNotFound(EndpointNotFoundException ex, WebRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler({InvalidSubmissionException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> onBadRequest(RuntimeException ex, WebRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnexpected(Exception ex, WebRequest req) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal error", req);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, WebRequest req) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                req.getDescription(false)
        );
        return ResponseEntity.status(status).body(body);
    }
}
