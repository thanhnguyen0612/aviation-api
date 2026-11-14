package com.sporty.aviation.domain;

import java.time.Duration;
import java.util.Optional;

/** The upstream airport data source could not be reached or refused the request. */
public class ProviderUnavailableException extends RuntimeException {

    private final Duration retryAfter;

    public ProviderUnavailableException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public ProviderUnavailableException(String message, Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
