package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.LoggerFactory;

import io.github.kubaj12.online_store.identityaccess.domain.LoginAttemptKey;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottlePolicy;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottleState;
import io.github.kubaj12.online_store.testsupport.TestClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptServiceTests {
	private static final Instant START = Instant.parse("2026-01-15T10:15:30Z");
	private static final String SOURCE = "192.0.2.10";

	@Test
	void fifthFailureBlocksAndBlocksFurtherCredentialLookup() {
		TestClock clock = clock();
		RecordingThrottleStore throttle = new RecordingThrottleStore();
		LoginAttemptService service = service(throttle, new RecordingAccountStore(), clock);
		int[] verifications = { 0 };
		for (int attempt = 0; attempt < 5; attempt++) {
			assertThatThrownBy(() -> service.authenticate("user@example.test", SOURCE,
					() -> { verifications[0]++; throw new BadCredentialsException("wrong"); }))
				.isInstanceOf(BadCredentialsException.class);
		}
		int completedVerifications = verifications[0];
		assertThat(throttle.state(LoginAttemptKey.of("user@example.test", SOURCE)).failedAttempts()).isEqualTo(5);
		assertThatThrownBy(() -> service.authenticate("user@example.test", SOURCE,
				() -> { verifications[0]++; return null; }))
				.isInstanceOf(BadCredentialsException.class);
		assertThat(verifications[0]).isEqualTo(completedVerifications);
	}

	@Test
	void credentialFailureNormalizesWindowAfterVerificationCrossesItsBoundary() {
		TestClock clock = clock();
		RecordingThrottleStore throttle = new RecordingThrottleStore();
		LoginThrottlePolicy policy = new LoginThrottlePolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofHours(24));
		LoginThrottleState state = policy.empty(START);
		for (int i = 0; i < 4; i++) state = policy.recordFailure(state, START);
		throttle.states.put(LoginAttemptKey.of("user@example.test", SOURCE), state);
		clock.set(START.plusSeconds(899));
		LoginAttemptService service = service(throttle, new RecordingAccountStore(), clock);
		assertThatThrownBy(() -> service.authenticate("user@example.test", SOURCE, () -> {
			clock.advance(Duration.ofSeconds(2));
			throw new BadCredentialsException("wrong");
		})).isInstanceOf(BadCredentialsException.class);
		LoginThrottleState result = throttle.state(LoginAttemptKey.of("user@example.test", SOURCE));
		assertThat(result.failedAttempts()).isOne();
		assertThat(result.blockedUntil()).isNull();
		assertThat(result.windowStartedAt()).isEqualTo(START.plusSeconds(901));
	}

	@Test
	void unexpectedSupplierFailureIsCauseFreeAndDoesNotExposeSensitiveMessage() {
		LoginAttemptService service = service(new RecordingThrottleStore(), new RecordingAccountStore(), clock());
		Logger serviceLogger = (Logger) LoggerFactory.getLogger(LoginAttemptService.class);
		Logger transactionLogger = (Logger) LoggerFactory.getLogger(TransactionTemplate.class);
		ListAppender<ILoggingEvent> serviceAppender = appender();
		ListAppender<ILoggingEvent> transactionAppender = appender();
		ch.qos.logback.classic.Level originalLevel = transactionLogger.getLevel();
		serviceLogger.addAppender(serviceAppender);
		transactionLogger.addAppender(transactionAppender);
		transactionLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
		try {
			assertThatThrownBy(() -> service.authenticate("user@example.test", SOURCE,
					() -> { throw new IllegalStateException("sensitive-row-value"); }))
					.isInstanceOf(AuthenticationServiceException.class)
					.hasMessageNotContaining("sensitive-row-value")
					.hasNoCause();
			assertThat(serviceAppender.list).isNotEmpty().allSatisfy(this::assertRedacted);
			assertThat(transactionAppender.list).isNotEmpty().allSatisfy(event ->
				assertThat(loggingText(event)).doesNotContain("sensitive-row-value"));
		}
		finally {
			transactionLogger.setLevel(originalLevel);
			serviceLogger.detachAppender(serviceAppender);
			transactionLogger.detachAppender(transactionAppender);
		}
	}

	@Test
	void commitFailuresAfterAdmissionAndCompletionAreCauseFreeAndRedacted() {
		RecordingThrottleStore throttle = new RecordingThrottleStore();
		RecordingAccountStore accounts = new RecordingAccountStore();
		FailingTransactionManager manager = new FailingTransactionManager();
		LoginAttemptService service = service(throttle, accounts, clock(), manager);
		withServiceLogs(events -> {
			manager.failCommit = new IllegalStateException("sensitive commit failure");
			assertOperational(() -> service.authenticate("user@example.test", SOURCE,
					() -> new org.springframework.security.authentication.TestingAuthenticationToken("user", "credentials")));
			manager.failCommit = new IllegalStateException("sensitive completion commit failure");
			assertOperational(() -> service.completeSuccessfulLogin(principal(), SOURCE));
			assertRedactedDiagnostics(events, "sensitive commit failure", "sensitive completion commit failure");
		});
	}

	@Test
	void rollbackFailureAfterSensitiveCallbackFailureKeepsTransactionLogsRedacted() {
		FailingTransactionManager manager = new FailingTransactionManager();
		manager.failRollback = new IllegalStateException("sensitive rollback failure");
		LoginAttemptService service = service(new RecordingThrottleStore(), new RecordingAccountStore(), clock(), manager);
		Logger transactionLogger = (Logger) LoggerFactory.getLogger(TransactionTemplate.class);
		Logger serviceLogger = (Logger) LoggerFactory.getLogger(LoginAttemptService.class);
		ListAppender<ILoggingEvent> transactionAppender = appender();
		ListAppender<ILoggingEvent> serviceAppender = appender();
		ch.qos.logback.classic.Level originalLevel = transactionLogger.getLevel();
		transactionLogger.addAppender(transactionAppender);
		serviceLogger.addAppender(serviceAppender);
		transactionLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
		try {
			assertOperational(() -> service.authenticate("user@example.test", SOURCE,
					() -> { throw new IllegalStateException("sensitive callback failure"); }));
			assertThat(transactionAppender.list).isNotEmpty().allSatisfy(event ->
				assertThat(loggingText(event)).doesNotContain("sensitive callback failure", "sensitive rollback failure"));
			assertRedactedDiagnostics(serviceAppender.list, "sensitive callback failure", "sensitive rollback failure");
		}
		finally {
			transactionLogger.setLevel(originalLevel);
			transactionLogger.detachAppender(transactionAppender);
			serviceLogger.detachAppender(serviceAppender);
		}
	}

	@Test
	void operationalPortFailuresAreGenericAndDoNotInvokeCredentials() {
		withServiceLogs(events -> {
		for (FailurePoint point : FailurePoint.values()) {
			RecordingThrottleStore throttle = new RecordingThrottleStore();
			RecordingAccountStore accounts = new RecordingAccountStore();
			String secret = "sensitive-" + point;
			switch (point) {
				case CLEANUP -> throttle.failCleanup = new IllegalStateException(secret);
				case LOCK -> throttle.failLock = new IllegalStateException(secret);
				case ADMISSION_SAVE -> throttle.failSave = new IllegalStateException(secret);
				case ACCOUNT_UPDATE -> accounts.failUpdate = new IllegalStateException(secret);
				case COMPLETION_RESET -> throttle.failSave = new IllegalStateException(secret);
				case COUNTED_REJECTION_SAVE -> throttle.failSave = new IllegalStateException(secret);
			}
			LoginAttemptService service = service(throttle, accounts, clock());
			AtomicInteger credentials = new AtomicInteger();
			if (point == FailurePoint.ACCOUNT_UPDATE || point == FailurePoint.COMPLETION_RESET) {
				assertOperational(() -> service.completeSuccessfulLogin(principal(), SOURCE));
			}
			else {
				// Save is reached before credential verification by using an expired state.
				if (point == FailurePoint.ADMISSION_SAVE) throttle.states.put(LoginAttemptKey.of("user@example.test", SOURCE),
						new LoginThrottlePolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofHours(24)).empty(START.minus(Duration.ofMinutes(16))));
				assertOperational(() -> service.authenticate("user@example.test", SOURCE, () -> {
					credentials.incrementAndGet();
					if (point == FailurePoint.COUNTED_REJECTION_SAVE) throw new BadCredentialsException("wrong");
					return new org.springframework.security.authentication.TestingAuthenticationToken("user", "credentials");
				}));
				assertThat(credentials).hasValue(point == FailurePoint.COUNTED_REJECTION_SAVE ? 1 : 0);
			}
		}
		assertRedactedDiagnostics(events, "sensitive-");
		});
	}

	private void withServiceLogs(java.util.function.Consumer<java.util.List<ILoggingEvent>> assertions) {
		Logger logger = (Logger) LoggerFactory.getLogger(LoginAttemptService.class);
		ListAppender<ILoggingEvent> appender = appender();
		logger.addAppender(appender);
		try { assertions.accept(appender.list); }
		finally { logger.detachAppender(appender); }
	}

	private void assertRedactedDiagnostics(java.util.List<ILoggingEvent> events, String... secrets) {
		assertThat(events).isNotEmpty().allSatisfy(event -> {
			assertThat(event.getThrowableProxy()).isNull();
			assertThat(event.getFormattedMessage()).contains("reference=").contains("exceptionType=");
			for (String secret : secrets) assertThat(loggingText(event)).doesNotContain(secret);
		});
	}

	private void assertOperational(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
		assertThatThrownBy(action).isInstanceOf(AuthenticationServiceException.class).hasNoCause()
				.hasMessageNotContaining("sensitive");
	}

	private static AccountPrincipal principal() {
		return new AccountPrincipal(UUID.randomUUID(), "user@example.test", "hash", "CUSTOMER", "ACTIVE", 0);
	}

	private enum FailurePoint { CLEANUP, LOCK, ADMISSION_SAVE, ACCOUNT_UPDATE, COMPLETION_RESET, COUNTED_REJECTION_SAVE }

	private static ListAppender<ILoggingEvent> appender() {
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		return appender;
	}

	private void assertRedacted(ILoggingEvent event) {
		assertThat(event.getFormattedMessage()).doesNotContain("sensitive-row-value");
		assertThat(event.getThrowableProxy()).isNull();
	}

	private static String loggingText(ILoggingEvent event) {
		StringBuilder text = new StringBuilder(event.getFormattedMessage());
		if (event.getThrowableProxy() != null) {
			appendThrowableText(text, event.getThrowableProxy());
		}
		return text.toString();
	}

	private static void appendThrowableText(StringBuilder text, ch.qos.logback.classic.spi.IThrowableProxy throwable) {
		text.append(throwable.getMessage());
		if (throwable.getCause() != null) appendThrowableText(text, throwable.getCause());
		for (var suppressed : throwable.getSuppressed()) appendThrowableText(text, suppressed);
	}

	private static TestClock clock() { TestClock clock = new TestClock(); clock.set(START); return clock; }

	private static LoginAttemptService service(RecordingThrottleStore throttle, RecordingAccountStore accounts, Clock clock) {
		return service(throttle, accounts, clock, new NoOpTransactionManager());
	}

	private static LoginAttemptService service(RecordingThrottleStore throttle, RecordingAccountStore accounts, Clock clock,
			PlatformTransactionManager transactionManager) {
		return new LoginAttemptService(throttle, accounts, new LoginThrottlePolicy(5, Duration.ofMinutes(15),
				Duration.ofMinutes(15), Duration.ofHours(24)), clock, new TransactionTemplate(transactionManager));
	}

	private static class NoOpTransactionManager implements PlatformTransactionManager {
		@Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
		@Override public void commit(TransactionStatus status) { }
		@Override public void rollback(TransactionStatus status) { }
	}

	private static final class FailingTransactionManager extends NoOpTransactionManager {
		private RuntimeException failCommit;
		private RuntimeException failRollback;
		@Override public void commit(TransactionStatus status) { if (failCommit != null) { RuntimeException failure = failCommit; failCommit = null; throw failure; } }
		@Override public void rollback(TransactionStatus status) { if (failRollback != null) { RuntimeException failure = failRollback; failRollback = null; throw failure; } }
	}

	private static final class RecordingThrottleStore implements LoginThrottleStore {
		private final Map<LoginAttemptKey, LoginThrottleState> states = new ConcurrentHashMap<>();
		private RuntimeException failCleanup;
		private RuntimeException failLock;
		private RuntimeException failSave;
		@Override public int deleteExpiredBatch(Instant now, int limit) { if (failCleanup != null) throw failCleanup; states.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now)); return 0; }
		@Override public LoginThrottleState lockOrCreate(LoginAttemptKey key, LoginThrottleState initialState) {
			if (failLock != null) throw failLock;
			return states.computeIfAbsent(key, ignored -> initialState);
		}
		@Override public void save(LoginAttemptKey key, LoginThrottleState state) { if (failSave != null) throw failSave; states.put(key, state); }
		private LoginThrottleState state(LoginAttemptKey key) { return states.get(key); }
	}

	private static final class RecordingAccountStore implements AuthenticationAccountStore {
        @Override public void registerSession(io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal principal,
                byte[] hash, Instant now, Instant expiresAt) { }

		private RuntimeException failUpdate;
		@Override public Optional<Credentials> findCredentialsByEmail(io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail email) { return Optional.empty(); }
		@Override public Optional<AccessSnapshot> findAccessById(UUID accountId) { return Optional.empty(); }
		@Override public boolean updateSuccessfulLogin(AccountPrincipal principal, Instant now) { if (failUpdate != null) throw failUpdate; return true; }
	}
}
