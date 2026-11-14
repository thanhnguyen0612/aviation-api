package com.sporty.aviation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "resilience4j.ratelimiter.instances.aviationWeather.limit-for-period=1")
class RateLimiterIntegrationTest {

    static final String EGLL = """
            [{"icaoId":"EGLL","iataId":"LHR","faaId":null,"name":"LONDON/HEATHROW AIRPORT","state":"EN",
            "country":"GB","lat":51.4719,"lon":-0.4638,"elev":26}]""";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort()).build();

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("aviation-weather.base-url", wm::baseUrl);
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

    private void stub(String icao, String body) {
        wm.stubFor(WireMock.get(urlPathEqualTo("/api/data/airport")).withQueryParam("ids", equalTo(icao))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void exceedingUpstreamQuotaReturns503WithRetryAfterAndSkipsUpstream() throws Exception {
        stub("KJFK", AirportApiIntegrationTest.KJFK);
        stub("EGLL", EGLL);

        mvc.perform(get("/api/v1/airports/KJFK")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/airports/EGLL"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"));

        wm.verify(0, getRequestedFor(urlPathEqualTo("/api/data/airport"))
                .withQueryParam("ids", equalTo("EGLL")));
    }
}
