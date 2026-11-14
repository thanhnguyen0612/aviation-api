package com.sporty.aviation.adapter.out.aviationweather;

import com.sporty.aviation.application.port.AirportProvider;
import com.sporty.aviation.domain.Airport;
import com.sporty.aviation.domain.ProviderUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Looks airports up on aviationweather.gov. Resilience4j applies Retry (outermost, owns the fallback),
 * then CircuitBreaker, then RateLimiter around {@link #findByIcao}.
 */
@Component
class AviationWeatherAirportProvider implements AirportProvider {

    private static final Logger log = LoggerFactory.getLogger(AviationWeatherAirportProvider.class);
    private static final String INSTANCE = "aviationWeather";

    private final RestClient restClient;
    private final RateLimiterRegistry rateLimiterRegistry;

    AviationWeatherAirportProvider(RestClient aviationWeatherRestClient, RateLimiterRegistry rateLimiterRegistry) {
        this.restClient = aviationWeatherRestClient;
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    @Override
    @Retry(name = INSTANCE, fallbackMethod = "fallback")
    @CircuitBreaker(name = INSTANCE)
    @RateLimiter(name = INSTANCE)
    public Optional<Airport> findByIcao(String icao) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            AviationWeatherAirport[] body = restClient.get()
                    .uri("/api/data/airport?ids={icao}&format=json", icao)
                    .retrieve()
                    .body(AviationWeatherAirport[].class);
            if (body == null || body.length == 0) {
                outcome = "not-found";
                return Optional.empty();
            }
            outcome = "found";
            return Optional.of(body[0].toDomain());
        } finally {
            log.info("aviationweather lookup icao={} outcome={} latencyMs={}", icao, outcome,
                    (System.nanoTime() - start) / 1_000_000);
        }
    }

    private Optional<Airport> fallback(String icao, Throwable t) {
        log.warn("aviationweather lookup failed icao={} exception={}", icao, t.getClass().getSimpleName());
        if (t instanceof RequestNotPermitted) {
            Duration retryAfter = rateLimiterRegistry.rateLimiter(INSTANCE)
                    .getRateLimiterConfig().getLimitRefreshPeriod();
            throw new ProviderUnavailableException("aviationweather.gov rate limit reached", t, retryAfter);
        }
        if (t instanceof HttpClientErrorException e && !isTooManyRequestsOrForbidden(e)) {
            throw e;
        }
        throw new ProviderUnavailableException("aviationweather.gov unavailable", t);
    }

    private static boolean isTooManyRequestsOrForbidden(HttpClientErrorException e) {
        return e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)
                || e.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN);
    }
}
