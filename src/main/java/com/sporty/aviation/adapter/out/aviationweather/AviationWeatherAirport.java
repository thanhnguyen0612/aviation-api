package com.sporty.aviation.adapter.out.aviationweather;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sporty.aviation.domain.Airport;

/** One element of the aviationweather.gov airport response; only the fields we use. */
@JsonIgnoreProperties(ignoreUnknown = true)
record AviationWeatherAirport(String icaoId, String iataId, String name, String state, String country,
                              Double lat, Double lon, Integer elev) {

    Airport toDomain() {
        String trimmedName = name == null ? null : name.trim();
        return new Airport(icaoId, iataId, trimmedName, state, country, lat, lon, elev);
    }
}
