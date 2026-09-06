package io.github.kubaj12.online_store.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/** Base fixture that isolates committed service-test state and resets deterministic adapters. */
@PostgreSqlServiceTest
public abstract class PostgreSqlServiceTestSupport {

	@Autowired
	private PostgreSqlDatabaseCleaner databaseCleaner;

	@Autowired
	private TestClock testClock;

	@Autowired
	private RecordingMailDelivery mailDelivery;

	@Autowired
	private InMemoryImageStorage imageStorage;

	@BeforeEach
	final void resetSharedFixtures() {
		databaseCleaner.clean();
		testClock.reset();
		mailDelivery.reset();
		imageStorage.reset();
	}

	protected final TestClock testClock() {
		return testClock;
	}

	protected final RecordingMailDelivery mailDelivery() {
		return mailDelivery;
	}

	protected final InMemoryImageStorage imageStorage() {
		return imageStorage;
	}

}
