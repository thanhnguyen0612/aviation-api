package com.sporty.aviation;

import com.sporty.aviation.application.port.AirportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class AviationApiApplicationTests {

	// Temporary: removed once the aviationweather adapter exists (Task 4).
	@MockitoBean
	AirportProvider airportProvider;

	@Test
	void contextLoads() {
	}

}
