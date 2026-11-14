package com.sporty.aviation.domain;

public class AirportNotFoundException extends RuntimeException {

    private final String icao;

    public AirportNotFoundException(String icao) {
        super("Airport not found: " + icao);
        this.icao = icao;
    }

    public String icao() {
        return icao;
    }
}
