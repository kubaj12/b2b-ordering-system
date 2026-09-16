package io.github.kubaj12.online_store;

import jakarta.validation.constraints.AssertTrue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail;
import io.github.kubaj12.online_store.identityaccess.domain.PasswordPolicy;

@Validated
@ConfigurationProperties("app.bootstrap.admin")
final class BootstrapAdminProperties {

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
		if (!enabled) {
			return true;
		}
		try {
			NormalizedEmail.of(email);
			return true;
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	@AssertTrue(message = "bootstrap administrator password must contain at least 12 characters when bootstrap is enabled")
	public boolean isPasswordValidWhenEnabled() {
		return !enabled || PasswordPolicy.hasMinimumCharacters(password);
	}

	@AssertTrue(message = "bootstrap administrator password must contain valid Unicode text and at most 72 UTF-8 bytes when bootstrap is enabled")
	public boolean isPasswordEncodingValidWhenEnabled() {
		return !enabled || PasswordPolicy.hasValidUtf8Length(password);
	}

	@Override
	public String toString() {
		return "BootstrapAdminProperties[enabled=%s, email=%s, password=<redacted>]"
				.formatted(enabled, email != null && !email.isBlank() ? "<configured>" : "<absent>");
	}

}
