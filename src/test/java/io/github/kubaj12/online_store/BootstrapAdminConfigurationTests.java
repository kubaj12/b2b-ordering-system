package io.github.kubaj12.online_store;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.kubaj12.online_store.identityaccess.application.InitialAdminBootstrapService;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapAdminConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(BootstrapAdminConfiguration.class)
			.withBean(BootstrapAdminProperties.class,
				() -> new BootstrapAdminProperties(true, "admin@example.com", "correct-horse-staple"));

	@Test
	void disabledBootstrapHasNoRunner() {
		contextRunner.withPropertyValues("app.bootstrap.admin.enabled=false")
				.run(context -> assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty());
	}

	@Test
	void enabledRunnerCallsServiceOnceWithConfiguredCredentials() throws Exception {
		RecordingBootstrapService service = new RecordingBootstrapService();
		contextRunner.withPropertyValues("app.bootstrap.admin.enabled=true")
				.withBean(InitialAdminBootstrapService.class, () -> service)
				.run(context -> {
					ApplicationRunner runner = context.getBean(ApplicationRunner.class);
					runner.run(new DefaultApplicationArguments(new String[0]));
					assertThat(service.calls).isOne();
					assertThat(service.email).isEqualTo("admin@example.com");
					assertThat(service.password).isEqualTo("correct-horse-staple");
				});
	}

	private static final class RecordingBootstrapService extends InitialAdminBootstrapService {

		private int calls;
		private String email;
		private String password;

		private RecordingBootstrapService() {
			super(null, null, null, null);
		}

		@Override
		public Outcome bootstrap(String email, String password) {
			calls++;
			this.email = email;
			this.password = password;
			return Outcome.CREATED;
		}
	}

}
