package io.github.kubaj12.online_store;

import java.time.Duration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottlePolicy;

@Validated
@ConfigurationProperties("app.security.login-throttle")
record LoginThrottleProperties(int maxFailures, @NotNull Duration window, @NotNull Duration blockDuration,
		@NotNull Duration retention) {
	@AssertTrue(message = "login throttle policy must have positive values and retention at least as long as the window and block")
	public boolean isValidPolicy() {
		try {
			new LoginThrottlePolicy(maxFailures, window, blockDuration, retention);
			return true;
		} catch (RuntimeException exception) { return false; }
	}
}
