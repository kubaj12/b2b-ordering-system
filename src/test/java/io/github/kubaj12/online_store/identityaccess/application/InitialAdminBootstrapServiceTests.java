package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitialAdminBootstrapServiceTests {

	private static final String PASSWORD = "synthetic-password-123";
	private static final Instant NOW = Instant.parse("2026-09-16T10:15:30.123456789Z");

	@Test
	void existingAccountSkipsHashingAndInsertion() {
		RecordingStore store = new RecordingStore();
		store.existing = true;
		RecordingEncoder encoder = new RecordingEncoder();

		assertThat(service(store, encoder).bootstrap(" Admin@Example.com ", PASSWORD))
				.isEqualTo(InitialAdminBootstrapService.Outcome.ALREADY_EXISTS);
		assertThat(encoder.calls).isZero();
		assertThat(store.insertCalls).isZero();
		assertThat(store.lookup).isEqualTo(NormalizedEmail.of("admin@example.com"));
	}

	@Test
	void createsUsingCanonicalEmailHashAndTruncatedClockInstant() {
		RecordingStore store = new RecordingStore();
		RecordingEncoder encoder = new RecordingEncoder();
		encoder.hash = "$2b$12$synthetic-hash";

		assertThat(service(store, encoder).bootstrap(" Admin@Example.com ", PASSWORD))
				.isEqualTo(InitialAdminBootstrapService.Outcome.CREATED);
		assertThat(encoder.password).isEqualTo(PASSWORD);
		assertThat(store.email).isEqualTo(NormalizedEmail.of("admin@example.com"));
		assertThat(store.passwordHash).isEqualTo(encoder.hash);
		assertThat(store.now).isEqualTo(NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
	}

	@Test
	void conditionalInsertRaceReturnsAlreadyExists() {
		RecordingStore store = new RecordingStore();
		store.insertResult = false;
		assertThat(service(store, new RecordingEncoder()).bootstrap("admin@example.com", PASSWORD))
				.isEqualTo(InitialAdminBootstrapService.Outcome.ALREADY_EXISTS);
	}

	@Test
	void callbackFailureIsSanitizedBeforeTransactionTemplateCanObserveIt() {
		RecordingStore store = new RecordingStore();
		store.failure = new IllegalStateException("raw-hash-secret database failing row");
		RecordingEncoder encoder = new RecordingEncoder();
		Logger logger = (Logger) LoggerFactory.getLogger(InitialAdminBootstrapService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertThatThrownBy(() -> service(store, encoder).bootstrap("admin@example.com", PASSWORD))
					.isInstanceOf(IllegalStateException.class)
						.hasMessageNotContaining("raw-hash-secret")
						.hasNoCause();
			assertThat(appender.list).allSatisfy(event -> {
				assertThat(event.getFormattedMessage()).doesNotContain("raw-hash-secret");
				assertThat(event.getThrowableProxy()).isNull();
			});
		}
		finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void transactionCompletionFailureIsSanitizedAtTheServiceBoundary() {
		RecordingStore store = new RecordingStore();
		PlatformTransactionManager manager = new PlatformTransactionManager() {
			@Override
			public TransactionStatus getTransaction(TransactionDefinition definition) {
				return null;
			}

			@Override
			public void commit(TransactionStatus status) {
				throw new IllegalStateException("commit-secret");
			}

			@Override
			public void rollback(TransactionStatus status) {
			}
		};
		Logger logger = (Logger) LoggerFactory.getLogger(InitialAdminBootstrapService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertThatThrownBy(() -> new InitialAdminBootstrapService(store, new RecordingEncoder(),
					Clock.fixed(NOW, ZoneOffset.UTC), transactionTemplate(manager))
					.bootstrap("admin@example.com", PASSWORD))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageNotContaining("commit-secret")
					.hasNoCause();
			assertThat(appender.list).allSatisfy(event -> {
				assertThat(event.getFormattedMessage()).doesNotContain("commit-secret");
				assertThat(event.getThrowableProxy()).isNull();
			});
		}
		finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void validationRunsBeforeStoreAndEncoder() {
		RecordingStore store = new RecordingStore();
		RecordingEncoder encoder = new RecordingEncoder();
		assertThatThrownBy(() -> service(store, encoder).bootstrap("invalid email", "short"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(store.lookup).isNull();
		assertThat(encoder.calls).isZero();
	}

	private static InitialAdminBootstrapService service(RecordingStore store, RecordingEncoder encoder) {
		return new InitialAdminBootstrapService(store, encoder, Clock.fixed(NOW, ZoneOffset.UTC), transactionTemplate());
	}

	private static TransactionTemplate transactionTemplate() {
		return transactionTemplate(new PlatformTransactionManager() {
			@Override
			public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
				return null;
			}

			@Override
			public void commit(TransactionStatus status) throws TransactionException {
			}

			@Override
			public void rollback(TransactionStatus status) throws TransactionException {
			}
		});
	}

	private static TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
		return new TransactionTemplate(manager);
	}

	private static final class RecordingStore implements InitialAdminAccountStore {

		private boolean existing;
		private boolean insertResult = true;
		private RuntimeException failure;
		private int insertCalls;
		private NormalizedEmail lookup;
		private NormalizedEmail email;
		private String passwordHash;
		private Instant now;

		@Override
		public boolean existsByEmail(NormalizedEmail email) {
			this.lookup = email;
			return existing;
		}

		@Override
		public boolean insertIfAbsent(UUID id, NormalizedEmail email, String passwordHash, Instant now) {
			insertCalls++;
			if (failure != null) {
				throw failure;
			}
			this.email = email;
			this.passwordHash = passwordHash;
			this.now = now;
			return insertResult;
		}
	}

	private static final class RecordingEncoder implements PasswordEncoder {

		private String password;
		private String hash = "hash";
		private int calls;

		@Override
		public String encode(CharSequence rawPassword) {
			calls++;
			password = rawPassword.toString();
			return hash;
		}

		@Override
		public boolean matches(CharSequence rawPassword, String encodedPassword) {
			return false;
		}

	}

}
