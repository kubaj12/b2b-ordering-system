package io.github.kubaj12.online_store;

import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.kubaj12.online_store.identityaccess.application.InitialAdminBootstrapService;
import io.github.kubaj12.online_store.identityaccess.application.InitialAdminBootstrapService.Outcome;

@Configuration(proxyBeanMethods = false)
class BootstrapAdminConfiguration {

	@Bean
	@ConditionalOnProperty(name = "app.bootstrap.admin.enabled", havingValue = "true")
	ApplicationRunner bootstrapAdminRunner(
			BootstrapAdminProperties properties,
			InitialAdminBootstrapService service
	) {
		return args -> {
			Outcome outcome = service.bootstrap(properties.email(), properties.password());
			if (outcome == Outcome.CREATED) {
				LoggerFactory.getLogger(BootstrapAdminConfiguration.class)
						.info("Initial administrator bootstrap created the configured account");
			}
			else {
				LoggerFactory.getLogger(BootstrapAdminConfiguration.class)
						.info("Initial administrator bootstrap skipped: an account already occupies the configured email; no account changes were made");
			}
		};
	}

}
