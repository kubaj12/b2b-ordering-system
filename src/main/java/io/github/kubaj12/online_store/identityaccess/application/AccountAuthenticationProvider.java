package io.github.kubaj12.online_store.identityaccess.application;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import io.github.kubaj12.online_store.identityaccess.domain.PasswordPolicy;

public final class AccountAuthenticationProvider extends DaoAuthenticationProvider {

	public AccountAuthenticationProvider(AccountUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
		super(userDetailsService);
		setPasswordEncoder(passwordEncoder);
		setHideUserNotFoundExceptions(true);
		setAlwaysPerformAdditionalChecksOnUser(true);
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		Object credentials = authentication.getCredentials();
		if (!(credentials instanceof String password)
				|| password.isBlank()
				|| !PasswordPolicy.hasValidUtf8Length(password)) {
			throw new BadCredentialsException("Invalid credentials");
		}
		try {
			return super.authenticate(authentication);
		}
		catch (IllegalArgumentException exception) {
			throw new BadCredentialsException("Invalid credentials");
		}
	}
}
