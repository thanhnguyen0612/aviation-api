package com.sporty.aviation.adapter.in.web;

import com.sporty.aviation.domain.Airport;

/** Public JSON shape of an airport, decoupled from the domain model. */
public record AirportResponse(
        String icao,
        String iata,
        String name,
        String region,
        String country,
        Double latitude,
        Double longitude,
        Integer elevationMeters) {

    static AirportResponse from(Airport airport) {
        return new AirportResponse(
                airport.icao(),
                airport.iata(),
                airport.name(),
                airport.region(),
                airport.country(),
                airport.latitude(),
                airport.longitude(),
                airport.elevationMeters());
    }
}
