package com.sporty.aviation.adapter.in.web;

import com.sporty.aviation.domain.AirportNotFoundException;
import com.sporty.aviation.domain.ProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Maps failures to RFC 9457 problem responses; never exposes upstream bodies or stack traces. */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AirportNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(AirportNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(problem(HttpStatus.NOT_FOUND, "Airport not found", ex.getMessage()));
    }

    @ExceptionHandler(ProviderUnavailableException.class)
    ResponseEntity<ProblemDetail> handleProviderUnavailable(ProviderUnavailableException ex) {
        log.warn("Provider unavailable: {}", ex.getMessage());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        ex.retryAfter().ifPresent(delay -> response.header(HttpHeaders.RETRY_AFTER, String.valueOf(delay.toSeconds())));
        return response.body(problem(HttpStatus.SERVICE_UNAVAILABLE, "Upstream provider unavailable",
                "Airport data is temporarily unavailable, please retry later"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "Unexpected error"));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
