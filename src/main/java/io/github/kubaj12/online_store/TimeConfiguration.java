package io.github.kubaj12.online_store;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.kubaj12.online_store.shared.time.ApplicationTimeZone;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApplicationTimeProperties.class)
class TimeConfiguration {

	@Bean
	Clock applicationClock() {
		return Clock.systemUTC();
	}

	@Bean
	ApplicationTimeZone applicationTimeZone(ApplicationTimeProperties properties) {
		return new ApplicationTimeZone(ZoneId.of(properties.displayZone()));
	}

}
