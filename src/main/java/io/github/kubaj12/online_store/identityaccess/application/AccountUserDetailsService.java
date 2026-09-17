package io.github.kubaj12.online_store.identityaccess.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.stereotype.Service;

import io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail;

@Service
public class AccountUserDetailsService implements UserDetailsService {

	private static final Logger LOGGER = LoggerFactory.getLogger(AccountUserDetailsService.class);
	private static final String NOT_FOUND = "Invalid credentials";

	private final AuthenticationAccountStore accountStore;

	public AccountUserDetailsService(AuthenticationAccountStore accountStore) {
		this.accountStore = accountStore;
	}

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		final NormalizedEmail email;
		try {
			email = NormalizedEmail.of(username);
		}
		catch (RuntimeException exception) {
			throw new UsernameNotFoundException(NOT_FOUND);
		}
		try {
			return accountStore.findCredentialsByEmail(email)
					.map(credentials -> new AccountPrincipal(credentials.id(), credentials.email(), credentials.passwordHash(),
							credentials.role(), credentials.status(), credentials.securityVersion()))
					.orElseThrow(() -> new UsernameNotFoundException(NOT_FOUND));
		}
		catch (UsernameNotFoundException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw sanitizedFailure(exception);
		}
	}

	private static InternalAuthenticationServiceException sanitizedFailure(RuntimeException failure) {
		String reference = UUID.randomUUID().toString();
		LOGGER.error("Account lookup failed; reference={}, exceptionType={}", reference, failure.getClass().getName());
		return new InternalAuthenticationServiceException("account lookup failed; reference=" + reference);
	}
}
