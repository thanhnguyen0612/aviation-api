package com.sporty.aviation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AirportApiIntegrationTest {

    static final String KJFK = """
            [{"icaoId":"KJFK","iataId":"JFK","faaId":"JFK","name":"NEW YORK/JOHN F KENNEDY INTL ","state":"NY",
            "country":"US","source":"FAA","type":"ARP","lat":40.6399,"lon":-73.7787,"elev":4,"magdec":"13W",
            "owner":"P","runways":[{"id":"04L/22R","dimension":"12079x200","surface":"C","alignment":31}]}]""";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort()).build();

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("aviation-weather.base-url", wm::baseUrl);
        registry.add("aviation-weather.read-timeout", () -> "500ms");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    CacheManager cacheManager;
    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void reset() {
        cacheManager.getCache("airports").clear();
        circuitBreakers.circuitBreaker("aviationWeather").reset();
    }

    private void stubStatus(int status) {
        wm.stubFor(WireMock.get(urlPathEqualTo("/api/data/airport")).willReturn(aResponse().withStatus(status)));
    }

    private void stubKjfk() {
        wm.stubFor(WireMock.get(urlPathEqualTo("/api/data/airport")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(KJFK)));
    }

    @Test
    void returnsAirportJson() throws Exception {
        stubKjfk();

        mvc.perform(get("/api/v1/airports/KJFK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icao").value("KJFK"))
                .andExpect(jsonPath("$.iata").value("JFK"))
                .andExpect(jsonPath("$.name").value("NEW YORK/JOHN F KENNEDY INTL"))
                .andExpect(jsonPath("$.region").value("NY"))
                .andExpect(jsonPath("$.country").value("US"))
                .andExpect(jsonPath("$.latitude").value(40.6399))
                .andExpect(jsonPath("$.longitude").value(-73.7787))
                .andExpect(jsonPath("$.elevationMeters").value(4));
    }

    @Test
    void secondLookupIsServedFromCacheIncludingLowercase() throws Exception {
        stubKjfk();

        mvc.perform(get("/api/v1/airports/kjfk"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.icao").value("KJFK"));
        mvc.perform(get("/api/v1/airports/KJFK"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.icao").value("KJFK"));

        wm.verify(1, getRequestedFor(urlPathEqualTo("/api/data/airport")));
    }

    @Test
    void unknownAirportReturns404AndIsNotCached() throws Exception {
        stubStatus(204);

        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/v1/airports/KXXX"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }

        wm.verify(2, getRequestedFor(urlPathEqualTo("/api/data/airport")));
    }

    @Test
    void invalidIcaoReturns400WithoutUpstreamCall() throws Exception {
        mvc.perform(get("/api/v1/airports/KJ")).andExpect(status().isBadRequest());

        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void upstreamOutageReturns503() throws Exception {
        wm.stubFor(WireMock.get(urlPathEqualTo("/api/data/airport")).willReturn(aResponse()
                .withStatus(503).withBody("secret-upstream-body")));

        String body = mvc.perform(get("/api/v1/airports/KJFK"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret-upstream-body");
    }
}
