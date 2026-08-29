package io.github.kubaj12.online_store;

import java.util.regex.Pattern;

import jakarta.validation.constraints.AssertTrue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.StringUtils;

@Validated
@ConfigurationProperties("app.bootstrap.admin")
final class BootstrapAdminProperties {

	private static final int MINIMUM_PASSWORD_LENGTH = 12;
	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

	private final boolean enabled;
	private final String email;
	private final String password;

	BootstrapAdminProperties(boolean enabled, String email, String password) {
		this.enabled = enabled;
		this.email = email;
		this.password = password;
	}

	boolean enabled() {
		return enabled;
	}

	String email() {
		return email;
	}

	String password() {
		return password;
	}

	@AssertTrue(message = "bootstrap administrator email must be configured and valid when bootstrap is enabled")
	public boolean isEmailValidWhenEnabled() {
		return !enabled || (StringUtils.hasText(email) && EMAIL_PATTERN.matcher(email).matches());
	}

	@AssertTrue(message = "bootstrap administrator password must contain at least 12 characters when bootstrap is enabled")
	public boolean isPasswordValidWhenEnabled() {
		return !enabled || (password != null && password.length() >= MINIMUM_PASSWORD_LENGTH);
	}

	@Override
	public String toString() {
		return "BootstrapAdminProperties[enabled=%s, email=%s, password=<redacted>]"
				.formatted(enabled, StringUtils.hasText(email) ? "<configured>" : "<absent>");
	}

}
