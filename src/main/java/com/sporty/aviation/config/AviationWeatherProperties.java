package com.sporty.aviation.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("aviation-weather")
public record AviationWeatherProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout) {
}
