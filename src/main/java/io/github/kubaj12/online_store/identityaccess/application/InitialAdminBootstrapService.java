package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail;
import io.github.kubaj12.online_store.identityaccess.domain.PasswordPolicy;

@Service
public class InitialAdminBootstrapService {

	private static final Logger LOGGER = LoggerFactory.getLogger(InitialAdminBootstrapService.class);

	public enum Outcome { CREATED, ALREADY_EXISTS }

	private final InitialAdminAccountStore accountStore;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;

	public InitialAdminBootstrapService(
			InitialAdminAccountStore accountStore,
			PasswordEncoder passwordEncoder,
			Clock clock,
			TransactionTemplate transactionTemplate
	) {
		this.accountStore = accountStore;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
		this.transactionTemplate = transactionTemplate;
	}

	public Outcome bootstrap(String email, String password) {
		NormalizedEmail normalizedEmail = NormalizedEmail.of(email);
		PasswordPolicy.validate(password);
		try {
			if (accountStore.existsByEmail(normalizedEmail)) {
				return Outcome.ALREADY_EXISTS;
			}
			String hash = passwordEncoder.encode(password);
			UUID id = UUID.randomUUID();
			Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
			Boolean inserted = transactionTemplate.execute(status -> {
				try {
					return accountStore.insertIfAbsent(id, normalizedEmail, hash, now);
				}
				catch (RuntimeException failure) {
					throw sanitizedFailure(failure);
				}
			});
			return Boolean.TRUE.equals(inserted) ? Outcome.CREATED : Outcome.ALREADY_EXISTS;
		}
		catch (RuntimeException failure) {
			if (failure instanceof SanitizedBootstrapFailure) {
				throw failure;
			}
			throw sanitizedFailure(failure);
		}
	}

	private static SanitizedBootstrapFailure sanitizedFailure(RuntimeException failure) {
		String reference = UUID.randomUUID().toString();
		LOGGER.error("Initial administrator bootstrap failed; reference={}, exceptionType={}",
				reference, failure.getClass().getName());
		return new SanitizedBootstrapFailure("initial administrator bootstrap failed; reference=" + reference);
	}

	private static final class SanitizedBootstrapFailure extends IllegalStateException {

		private static final long serialVersionUID = 1L;

		private SanitizedBootstrapFailure(String message) {
			super(message);
		}

	}

}
