package com.sporty.aviation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sporty.aviation.application.port.AirportProvider;
import com.sporty.aviation.domain.Airport;
import com.sporty.aviation.domain.AirportNotFoundException;
import com.sporty.aviation.domain.ProviderUnavailableException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetAirportByIcaoUseCaseTest {

    private static final Airport JFK = new Airport("KJFK", "JFK", "NEW YORK/JOHN F KENNEDY INTL",
            "NY", "US", 40.6399, -73.7787, 4);

    @Mock
    AirportProvider provider;

    @InjectMocks
    GetAirportByIcaoUseCase useCase;

    @Test
    void returnsAirportFromProviderUsingUpperCaseCode() {
        when(provider.findByIcao("KJFK")).thenReturn(Optional.of(JFK));

        assertThat(useCase.execute("kjfk")).isEqualTo(JFK);
        verify(provider).findByIcao("KJFK");
    }

    @Test
    void throwsNotFoundWhenProviderReturnsEmpty() {
        when(provider.findByIcao("ZZZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute("ZZZZ"))
                .isInstanceOf(AirportNotFoundException.class)
                .hasMessage("Airport not found: ZZZZ");
    }

    @Test
    void propagatesProviderUnavailable() {
        when(provider.findByIcao("KJFK")).thenThrow(new ProviderUnavailableException("down", null));

        assertThatThrownBy(() -> useCase.execute("KJFK"))
                .isInstanceOf(ProviderUnavailableException.class);
    }
}
