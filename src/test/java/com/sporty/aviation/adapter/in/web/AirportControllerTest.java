package com.sporty.aviation.adapter.in.web;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sporty.aviation.application.GetAirportByIcaoUseCase;
import com.sporty.aviation.domain.Airport;
import com.sporty.aviation.domain.AirportNotFoundException;
import com.sporty.aviation.domain.ProviderUnavailableException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

@WebMvcTest(AirportController.class)
class AirportControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    GetAirportByIcaoUseCase useCase;

    @Test
    void returnsAirport() throws Exception {
        when(useCase.execute("KJFK")).thenReturn(new Airport(
                "KJFK", "JFK", "John F Kennedy Intl", "NY", "US", 40.6399, -73.7787, 4));

        mockMvc.perform(get("/api/v1/airports/KJFK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icao").value("KJFK"))
                .andExpect(jsonPath("$.iata").value("JFK"))
                .andExpect(jsonPath("$.name").value("John F Kennedy Intl"))
                .andExpect(jsonPath("$.region").value("NY"))
                .andExpect(jsonPath("$.country").value("US"))
                .andExpect(jsonPath("$.latitude").value(40.6399))
                .andExpect(jsonPath("$.longitude").value(-73.7787))
                .andExpect(jsonPath("$.elevationMeters").value(4));
    }

    @ParameterizedTest
    @ValueSource(strings = {"KJF", "KJFKX", "K!@#", "KJ K"})
    void rejectsInvalidIcao(String icao) throws Exception {
        URI uri = UriComponentsBuilder.fromPath("/api/v1/airports/{icao}").build(icao);

        mockMvc.perform(get(uri))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(useCase);
    }

    @Test
    void returns404WhenNotFound() throws Exception {
        when(useCase.execute("ZZZZ")).thenThrow(new AirportNotFoundException("ZZZZ"));

        mockMvc.perform(get("/api/v1/airports/ZZZZ"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Airport not found"))
                .andExpect(jsonPath("$.detail").value("Airport not found: ZZZZ"));
    }

    @Test
    void returns503WhenProviderUnavailable() throws Exception {
        when(useCase.execute("KJFK")).thenThrow(new ProviderUnavailableException("x", new RuntimeException()));

        mockMvc.perform(get("/api/v1/airports/KJFK"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.title").value("Upstream provider unavailable"))
                .andExpect(jsonPath("$.detail")
                        .value("Airport data is temporarily unavailable, please retry later"));
    }

    @Test
    void returns503WithRetryAfterWhenRateLimited() throws Exception {
        when(useCase.execute("KJFK")).thenThrow(
                new ProviderUnavailableException("limited", new RuntimeException(), Duration.ofSeconds(60)));

        mockMvc.perform(get("/api/v1/airports/KJFK"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void returns500WithoutLeakingDetails() throws Exception {
        when(useCase.execute("KJFK")).thenThrow(new IllegalStateException("secret upstream body"));

        mockMvc.perform(get("/api/v1/airports/KJFK"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Unexpected error"))
                .andExpect(content().string(not(containsString("secret"))));
    }
}
