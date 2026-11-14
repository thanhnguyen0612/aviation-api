package com.sporty.aviation.application.port;

import com.sporty.aviation.domain.Airport;
import java.util.Optional;

/** Output port: where airport data comes from. */
public interface AirportProvider {

    /** @param icao upper-case ICAO code */
    Optional<Airport> findByIcao(String icao);
}
