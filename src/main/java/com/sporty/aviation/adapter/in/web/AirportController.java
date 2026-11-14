package com.sporty.aviation.adapter.in.web;

import com.sporty.aviation.application.GetAirportByIcaoUseCase;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/airports")
class AirportController {

    private final GetAirportByIcaoUseCase getAirportByIcao;

    AirportController(GetAirportByIcaoUseCase getAirportByIcao) {
        this.getAirportByIcao = getAirportByIcao;
    }

    @GetMapping("/{icao}")
    AirportResponse getAirport(@PathVariable @Pattern(regexp = "^[A-Za-z0-9]{4}$") String icao) {
        return AirportResponse.from(getAirportByIcao.execute(icao));
    }
}
