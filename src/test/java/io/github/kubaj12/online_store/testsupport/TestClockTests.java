package io.github.kubaj12.online_store.testsupport;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestClockTests {

	@Test
	void canBeSetAdvancedAndResetDeterministically() {
		var clock = new TestClock();
		clock.set(Instant.parse("2027-02-01T10:00:00Z"));

		clock.advance(Duration.ofMinutes(15));

		assertThat(clock.instant()).isEqualTo(Instant.parse("2027-02-01T10:15:00Z"));
		clock.reset();
		assertThat(clock.instant()).isEqualTo(TestClock.DEFAULT_INSTANT);
	}

	@Test
	void sharesTheControlledInstantWithZoneAdjustedViews() {
		var clock = new TestClock();
		var warsawView = clock.withZone(ZoneId.of("Europe/Warsaw"));

		clock.advance(Duration.ofHours(2));

		assertThat(warsawView.instant()).isEqualTo(clock.instant());
		assertThat(warsawView.getZone()).isEqualTo(ZoneId.of("Europe/Warsaw"));
	}

}
