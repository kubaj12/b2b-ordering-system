package io.github.kubaj12.online_store.identityaccess.application;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import io.github.kubaj12.online_store.identityaccess.domain.PasswordPolicy;

public final class AccountAuthenticationProvider extends DaoAuthenticationProvider {
	private final LoginAttemptService loginAttemptService;

	public AccountAuthenticationProvider(AccountUserDetailsService userDetailsService, PasswordEncoder passwordEncoder,
			LoginAttemptService loginAttemptService) {
		super(userDetailsService);
		this.loginAttemptService = loginAttemptService;
		setPasswordEncoder(passwordEncoder);
		setHideUserNotFoundExceptions(true);
		setAlwaysPerformAdditionalChecksOnUser(true);
		// A blocked account must still take the password-comparison path, just like a missing account.
		// The explicit status rejection below keeps the externally visible result unchanged.
		setPreAuthenticationChecks(user -> { });
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		if (!(authentication.getDetails() instanceof WebAuthenticationDetails details)) {
			throw new BadCredentialsException("Invalid credentials");
		}
		return loginAttemptService.authenticate(authentication.getName(), details.getRemoteAddress(),
				() -> authenticateCredentials(authentication));
	}

	private Authentication authenticateCredentials(Authentication authentication) {
		Object credentials = authentication.getCredentials();
		if (!(credentials instanceof String password)
				|| password.isBlank()
				|| !PasswordPolicy.hasValidUtf8Length(password)) {
			throw new BadCredentialsException("Invalid credentials");
		}
		try {
			Authentication result = super.authenticate(authentication);
			if (result.getPrincipal() instanceof AccountPrincipal principal && !"ACTIVE".equals(principal.status())) {
				throw new BadCredentialsException("Invalid credentials");
			}
			return result;
		}
		catch (IllegalArgumentException exception) {
			throw new BadCredentialsException("Invalid credentials");
		}
	}
}
