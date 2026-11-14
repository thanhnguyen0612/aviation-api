package com.sporty.aviation.adapter.in.web;

import com.sporty.aviation.application.GetAirportByIcaoUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/airports")
@Tag(name = "Airports")
class AirportController {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final GetAirportByIcaoUseCase getAirportByIcao;

    AirportController(GetAirportByIcaoUseCase getAirportByIcao) {
        this.getAirportByIcao = getAirportByIcao;
    }

    @GetMapping("/{icao}")
    @Operation(summary = "Get airport details by ICAO code")
    @ApiResponse(responseCode = "200", description = "Airport found")
    @ApiResponse(responseCode = "400", description = "ICAO code is not 4 letters or digits",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Airport not found",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Upstream provider unavailable or outbound rate limit reached",
            headers = @Header(name = "Retry-After", description = "Seconds to wait; sent only when our rate limit is reached",
                    schema = @Schema(type = "integer")),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected error",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    AirportResponse getAirport(
            @Parameter(description = "4-character ICAO code, case-insensitive", example = "KJFK")
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9]{4}$") String icao) {
        return AirportResponse.from(getAirportByIcao.execute(icao));
    }
}
