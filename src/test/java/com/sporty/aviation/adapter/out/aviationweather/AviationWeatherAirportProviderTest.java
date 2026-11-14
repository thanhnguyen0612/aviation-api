package com.sporty.aviation.adapter.out.aviationweather;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.sporty.aviation.application.port.AirportProvider;
import com.sporty.aviation.domain.Airport;
import com.sporty.aviation.domain.ProviderUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;

@SpringBootTest
@ActiveProfiles("test")
class AviationWeatherAirportProviderTest {

    static final String KJFK = """
            [{"icaoId":"KJFK","iataId":"JFK","faaId":"JFK","name":"NEW YORK/JOHN F KENNEDY INTL ","state":"NY",
            "country":"US","source":"FAA","type":"ARP","lat":40.6399,"lon":-73.7787,"elev":4,"magdec":"13W",
            "owner":"P","runways":[{"id":"04L/22R","dimension":"12079x200","surface":"C","alignment":31}]}]""";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(
            com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort()).build();

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

    private void stubStatus(int status) {
        wm.stubFor(get(urlPathEqualTo("/api/data/airport")).willReturn(aResponse().withStatus(status)));
    }

    private void stubJson(String body) {
        wm.stubFor(get(urlPathEqualTo("/api/data/airport")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void verifyRequests(int count) {
        wm.verify(count, getRequestedFor(urlPathEqualTo("/api/data/airport")));
    }

    @Test
    void mapsSuccessfulResponse() {
        stubJson(KJFK);

        assertThat(provider.findByIcao("KJFK")).contains(
                new Airport("KJFK", "JFK", "NEW YORK/JOHN F KENNEDY INTL", "NY", "US", 40.6399, -73.7787, 4));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/api/data/airport"))
                .withQueryParam("ids", equalTo("KJFK")).withQueryParam("format", equalTo("json")));
    }

    @Test
    void mapsNullFieldsWithoutError() {
        stubJson("""
                [{"icaoId":"EGLL","iataId":null,"name":null,"state":null,"country":"GB","lat":51.4719,"lon":-0.4638}]""");

        Airport airport = provider.findByIcao("EGLL").orElseThrow();

        assertThat(airport.name()).isNull();
        assertThat(airport.iata()).isNull();
        assertThat(airport.region()).isNull();
        assertThat(airport.elevationMeters()).isNull();
    }

    @Test
    void returnsEmptyOn204() {
        stubStatus(204);

        assertThat(provider.findByIcao("ZZZZ")).isEmpty();
    }

    @Test
    void returnsEmptyOnEmptyArray() {
        stubJson("[]");

        assertThat(provider.findByIcao("ZZZZ")).isEmpty();
    }

    @Test
    void retriesServerErrorsThenThrowsUnavailable() {
        stubStatus(500);

        assertThatThrownBy(() -> provider.findByIcao("KJFK")).isInstanceOf(ProviderUnavailableException.class);
        verifyRequests(3);
    }

    @Test
    void doesNotRetry429() {
        stubStatus(429);

        assertThatThrownBy(() -> provider.findByIcao("KJFK")).isInstanceOf(ProviderUnavailableException.class);
        verifyRequests(1);
    }

    @Test
    void doesNotRetry403() {
        stubStatus(403);

        assertThatThrownBy(() -> provider.findByIcao("KJFK")).isInstanceOf(ProviderUnavailableException.class);
        verifyRequests(1);
    }

    @Test
    void rethrowsUnexpected4xx() {
        stubStatus(400);

        assertThatThrownBy(() -> provider.findByIcao("KJFK"))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
        verifyRequests(1);
    }

    @Test
    void timeoutIsRetriedThenUnavailable() {
        wm.stubFor(get(urlPathEqualTo("/api/data/airport")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(KJFK)
                .withFixedDelay(1000)));

        assertThatThrownBy(() -> provider.findByIcao("KJFK")).isInstanceOf(ProviderUnavailableException.class);
        verifyRequests(3);
    }

    @Test
    void malformedBodyIsUnavailable() {
        stubJson("<html>oops</html>");

        assertThatThrownBy(() -> provider.findByIcao("KJFK")).isInstanceOf(ProviderUnavailableException.class);
        verifyRequests(1);
    }
}
