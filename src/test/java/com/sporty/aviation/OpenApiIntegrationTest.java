package com.sporty.aviation;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiIntegrationTest {

    private static final String AIRPORT_GET = "$.paths['/api/v1/airports/{icao}'].get";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void documentsAirportEndpointWithAllResponses() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Aviation API"))
                .andExpect(jsonPath(AIRPORT_GET + ".parameters[0].name").value("icao"))
                .andExpect(jsonPath(AIRPORT_GET + ".parameters[0].schema.pattern").value("^[A-Za-z0-9]{4}$"))
                .andExpect(jsonPath(AIRPORT_GET + ".responses['200']").exists())
                .andExpect(jsonPath(AIRPORT_GET + ".responses['400']").exists())
                .andExpect(jsonPath(AIRPORT_GET + ".responses['404']").exists())
                .andExpect(jsonPath(AIRPORT_GET + ".responses['503'].headers['Retry-After']").exists())
                .andExpect(jsonPath(AIRPORT_GET + ".responses['500']").exists());
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));
    }
}
