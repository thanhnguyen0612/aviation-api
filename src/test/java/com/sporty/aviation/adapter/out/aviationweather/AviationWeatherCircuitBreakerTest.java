package com.sporty.aviation.adapter.out.aviationweather;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.sporty.aviation.application.port.AirportProvider;
import com.sporty.aviation.domain.ProviderUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "resilience4j.circuitbreaker.instances.aviationWeather.minimum-number-of-calls=2",
        "resilience4j.circuitbreaker.instances.aviationWeather.sliding-window-size=2"})
class AviationWeatherCircuitBreakerTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort()).build();

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("aviation-weather.base-url", wm::baseUrl);
        registry.add("aviation-weather.read-timeout", () -> "500ms");
    }

    @Autowired
    AirportProvider provider;
    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void resetBreaker() {
        circuitBreakers.circuitBreaker("aviationWeather").reset();
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        wm.stubFor(get(urlPathEqualTo("/api/data/airport")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> provider.findByIcao("KJFK")).isInstanceOf(ProviderUnavailableException.class);
        assertThat(circuitBreakers.circuitBreaker("aviationWeather").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/api/data/airport")));

        assertThatThrownBy(() -> provider.findByIcao("KJFK"))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/api/data/airport")));
    }
}
