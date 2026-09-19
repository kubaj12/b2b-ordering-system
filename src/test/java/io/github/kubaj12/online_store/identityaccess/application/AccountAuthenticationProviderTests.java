package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.identityaccess.domain.LoginAttemptKey;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottlePolicy;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottleState;
import io.github.kubaj12.online_store.testsupport.TestClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountAuthenticationProviderTests {
	@Test
	void unknownAndBlockedAccountsBothPerformPasswordMatchingBeforeGenericRejection() {
		RecordingEncoder encoder = new RecordingEncoder();
		AuthenticationAccountStore store = new AuthenticationAccountStore() {
            @Override public void registerSession(AccountPrincipal principal, byte[] hash, java.time.Instant now, java.time.Instant expiresAt) { }

			@Override public Optional<Credentials> findCredentialsByEmail(io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail email) {
				if (email.value().equals("blocked@example.test")) return Optional.of(new Credentials(UUID.randomUUID(), email.value(),
						"stored-hash", "CUSTOMER", "BLOCKED", 0));
				return Optional.empty();
			}
			@Override public Optional<AccessSnapshot> findAccessById(UUID id) { return Optional.empty(); }
			@Override public boolean updateSuccessfulLogin(AccountPrincipal principal, Instant now) { return true; }
		};
		LoginAttemptService attempts = new LoginAttemptService(new NoOpThrottleStore(), store,
				new LoginThrottlePolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofHours(24)),
				new TestClock(), new TransactionTemplate(new NoOpTransactionManager()));
		AccountAuthenticationProvider provider = new AccountAuthenticationProvider(new AccountUserDetailsService(store), encoder, attempts);

		assertThatThrownBy(() -> provider.authenticate(request("missing@example.test"))).isInstanceOf(BadCredentialsException.class);
		int matchesForMissing = encoder.matches.get();
		assertThatThrownBy(() -> provider.authenticate(request("blocked@example.test"))).isInstanceOf(BadCredentialsException.class);
		assertThat(matchesForMissing).isPositive();
		assertThat(encoder.matches.get()).isGreaterThan(matchesForMissing);
	}

	private static Authentication request(String email) {
		UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.unauthenticated(email, "password");
		token.setDetails(new WebAuthenticationDetails("192.0.2.1", null));
		return token;
	}

	private static final class RecordingEncoder implements PasswordEncoder {
		private final AtomicInteger matches = new AtomicInteger();
		@Override public String encode(CharSequence rawPassword) { return "dummy-hash"; }
		@Override public boolean matches(CharSequence rawPassword, String encodedPassword) { matches.incrementAndGet(); return false; }
	}
	private static final class NoOpThrottleStore implements LoginThrottleStore {
		@Override public int deleteExpiredBatch(Instant now, int limit) { return 0; }
		@Override public LoginThrottleState lockOrCreate(LoginAttemptKey key, LoginThrottleState initial) { return initial; }
		@Override public void save(LoginAttemptKey key, LoginThrottleState state) { }
	}
	private static final class NoOpTransactionManager implements PlatformTransactionManager {
		@Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
		@Override public void commit(TransactionStatus status) { }
		@Override public void rollback(TransactionStatus status) { }
	}
}
