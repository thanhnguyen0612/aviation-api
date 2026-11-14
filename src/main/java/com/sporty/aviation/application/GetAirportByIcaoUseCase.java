package com.sporty.aviation.application;

import com.sporty.aviation.application.port.AirportProvider;
import com.sporty.aviation.domain.Airport;
import com.sporty.aviation.domain.AirportNotFoundException;
import java.util.Locale;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class GetAirportByIcaoUseCase {

    private final AirportProvider provider;

    public GetAirportByIcaoUseCase(AirportProvider provider) {
        this.provider = provider;
    }

    @Cacheable(cacheNames = "airports", key = "#icao.toUpperCase()")
    public Airport execute(String icao) {
        String code = icao.toUpperCase(Locale.ROOT);
        return provider.findByIcao(code).orElseThrow(() -> new AirportNotFoundException(code));
    }
}
