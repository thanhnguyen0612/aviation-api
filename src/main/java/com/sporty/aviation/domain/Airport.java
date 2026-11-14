package com.sporty.aviation.domain;

public record Airport(
        String icao,
        String iata,
        String name,
        String region,
        String country,
        Double latitude,
        Double longitude,
        Integer elevationMeters) {
}
