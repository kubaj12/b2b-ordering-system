package io.github.kubaj12.online_store.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Mutable UTC clock reset to a known instant before every service integration test. */
public final class TestClock extends Clock {

	public static final Instant DEFAULT_INSTANT = Instant.parse("2026-01-15T10:15:30Z");

	private final AtomicReference<Instant> currentInstant;
	private final ZoneId zone;

	public TestClock() {
		this(new AtomicReference<>(DEFAULT_INSTANT), ZoneOffset.UTC);
	}

	private TestClock(AtomicReference<Instant> currentInstant, ZoneId zone) {
		this.currentInstant = currentInstant;
		this.zone = zone;
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId requestedZone) {
		return new TestClock(currentInstant, Objects.requireNonNull(requestedZone, "zone must not be null"));
	}

	@Override
	public Instant instant() {
		return currentInstant.get();
	}

	public void set(Instant instant) {
		currentInstant.set(Objects.requireNonNull(instant, "instant must not be null"));
	}

	public void advance(Duration duration) {
		Objects.requireNonNull(duration, "duration must not be null");
		currentInstant.updateAndGet(instant -> instant.plus(duration));
	}

	public void reset() {
		currentInstant.set(DEFAULT_INSTANT);
	}

}
