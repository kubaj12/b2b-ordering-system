package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.identityaccess.domain.LoginAttemptKey;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottlePolicy;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottleState;

/** Coordinates persistent admission, credential verification, and post-login completion. */
@Service
public final class LoginAttemptService {
	private static final Logger LOGGER = LoggerFactory.getLogger(LoginAttemptService.class);
	private static final String INVALID = "Invalid credentials";
	private final LoginThrottleStore throttleStore;
	private final AuthenticationAccountStore accountStore;
	private final LoginThrottlePolicy policy;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;

	public LoginAttemptService(LoginThrottleStore throttleStore, AuthenticationAccountStore accountStore,
			LoginThrottlePolicy policy, Clock clock, TransactionTemplate transactionTemplate) {
		this.throttleStore = throttleStore;
		this.accountStore = accountStore;
		this.policy = policy;
		this.clock = clock;
		this.transactionTemplate = new TransactionTemplate(transactionTemplate.getTransactionManager());
		this.transactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
		this.transactionTemplate.setTimeout(15);
	}

	public Authentication authenticate(String attemptedIdentity, String sourceAddress,
			Supplier<Authentication> verifyCredentials) {
		final LoginAttemptKey key;
		try {
			key = LoginAttemptKey.of(attemptedIdentity, sourceAddress);
		}
		catch (RuntimeException exception) {
			throw invalid();
		}
		try {
			Outcome outcome = transactionTemplate.execute(status -> authenticateInTransaction(key, verifyCredentials));
			if (outcome != null && outcome.authentication != null) return outcome.authentication;
		}
		catch (SanitizedLoginAttemptException exception) {
			throw new AuthenticationServiceException(exception.getMessage());
		}
		catch (AuthenticationServiceException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw operational("login admission failed", exception);
		}
		throw invalid();
	}

	private Outcome authenticateInTransaction(LoginAttemptKey key, Supplier<Authentication> verifyCredentials) {
		try {
			Instant now = micros(clock.instant());
			throttleStore.deleteExpiredBatch(now, 100);
			LoginThrottleState state = throttleStore.lockOrCreate(key, policy.empty(now));
			Instant lockedNow = policy.effectiveNow(clock.instant(), state);
			if (policy.blocked(state, lockedNow)) return Outcome.REJECTED;
			LoginThrottleState normalized = policy.normalize(state, lockedNow);
			if (normalized != state) throttleStore.save(key, normalized);
			try {
				return Outcome.accepted(verifyCredentials.get());
			}
			catch (AuthenticationException exception) {
				if (exception instanceof AuthenticationServiceException) throw callbackFailure("credential verification failed", exception);
				Instant failureNow = policy.effectiveNow(clock.instant(), normalized);
				LoginThrottleState failureState = policy.normalize(normalized, failureNow);
				throttleStore.save(key, policy.recordFailure(failureState, failureNow));
				return Outcome.REJECTED;
			}
		}
		catch (SanitizedLoginAttemptException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw callbackFailure("login admission failed", exception);
		}
	}

	public boolean completeSuccessfulLogin(AccountPrincipal principal, String sourceAddress) {
		final LoginAttemptKey key;
		try {
			key = LoginAttemptKey.of(principal.email(), sourceAddress);
		}
		catch (RuntimeException exception) {
			return false;
		}
		try {
			Boolean completed = transactionTemplate.execute(status -> {
				try {
					Instant now = micros(clock.instant());
					throttleStore.deleteExpiredBatch(now, 100);
					LoginThrottleState state = throttleStore.lockOrCreate(key, policy.empty(now));
					Instant completionNow = policy.effectiveNow(clock.instant(), state);
					if (accountStore.updateSuccessfulLogin(principal, completionNow)) {
						throttleStore.save(key, policy.reset(state, completionNow));
						return true;
					}
					if (!policy.blocked(state, completionNow)) {
						throttleStore.save(key, policy.recordFailure(policy.normalize(state, completionNow), completionNow));
					}
					return false;
				}
				catch (RuntimeException exception) {
					throw callbackFailure("successful login completion failed", exception);
				}
			});
			return Boolean.TRUE.equals(completed);
		}
		catch (SanitizedLoginAttemptException exception) {
			throw new AuthenticationServiceException(exception.getMessage());
		}
		catch (RuntimeException exception) {
			throw operational("successful login completion failed", exception);
		}
	}

	private static SanitizedLoginAttemptException callbackFailure(String operation, RuntimeException failure) {
		String reference = UUID.randomUUID().toString();
		LOGGER.error("{}; reference={}, exceptionType={}", operation, reference, failure.getClass().getName());
		return new SanitizedLoginAttemptException(operation + "; reference=" + reference);
	}

	private static Instant micros(Instant instant) { return instant.truncatedTo(ChronoUnit.MICROS); }
	private static BadCredentialsException invalid() { return new BadCredentialsException(INVALID); }
	private static AuthenticationServiceException operational(String operation, RuntimeException failure) {
		String reference = UUID.randomUUID().toString();
		LOGGER.error("{}; reference={}, exceptionType={}", operation, reference, failure.getClass().getName());
		return new AuthenticationServiceException(operation + "; reference=" + reference);
	}

	private static final class SanitizedLoginAttemptException extends RuntimeException {
		private SanitizedLoginAttemptException(String message) { super(message, null, false, false); }
	}

	private record Outcome(Authentication authentication) {
		private static Outcome accepted(Authentication authentication) { return new Outcome(authentication); }
		private static final Outcome REJECTED = new Outcome(null);
	}
}
