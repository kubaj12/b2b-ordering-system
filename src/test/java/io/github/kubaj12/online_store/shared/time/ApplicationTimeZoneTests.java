package io.github.kubaj12.online_store.shared.time;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationTimeZoneTests {

	private final ApplicationTimeZone applicationTimeZone =
			new ApplicationTimeZone(ZoneId.of("Europe/Warsaw"));

	@Test
	void appliesWarsawDaylightSavingRules() {
		assertThat(applicationTimeZone.at(Instant.parse("2026-01-15T12:00:00Z")).getOffset())
				.isEqualTo(ZoneOffset.ofHours(1));
		assertThat(applicationTimeZone.at(Instant.parse("2026-07-15T12:00:00Z")).getOffset())
				.isEqualTo(ZoneOffset.ofHours(2));
	}

	@Test
	void derivesTheCalendarYearAtTheWarsawNewYearBoundary() {
		assertThat(applicationTimeZone.yearAt(Instant.parse("2026-12-31T22:59:59.999999Z")))
				.isEqualTo(2026);
		assertThat(applicationTimeZone.yearAt(Instant.parse("2026-12-31T23:00:00Z")))
				.isEqualTo(2027);
	}

}
