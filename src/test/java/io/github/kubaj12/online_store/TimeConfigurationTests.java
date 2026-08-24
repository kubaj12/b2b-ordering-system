package io.github.kubaj12.online_store;

import java.time.Clock;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.kubaj12.online_store.shared.time.ApplicationTimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class TimeConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(TimeConfiguration.class);

	@Test
	void configuresUtcClockAndWarsawApplicationZone() {
		contextRunner
				.withPropertyValues("app.time.display-zone=Europe/Warsaw")
				.run(context -> {
					assertThat(context).hasSingleBean(Clock.class);
					assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);

					assertThat(context).hasSingleBean(ApplicationTimeZone.class);
					assertThat(context.getBean(ApplicationTimeZone.class).zoneId())
							.isEqualTo(java.time.ZoneId.of("Europe/Warsaw"));
				});
	}

	@Test
	void rejectsAnInvalidApplicationZone() {
		contextRunner
				.withPropertyValues("app.time.display-zone=Not/A-Time-Zone")
				.run(context -> assertThat(context).hasFailed());
	}

}
