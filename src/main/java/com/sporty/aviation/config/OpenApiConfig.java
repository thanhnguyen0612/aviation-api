package com.sporty.aviation.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI aviationOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Aviation API")
                .description("Airport details by ICAO code, backed by aviationweather.gov.")
                .version("v1"));
    }
}
