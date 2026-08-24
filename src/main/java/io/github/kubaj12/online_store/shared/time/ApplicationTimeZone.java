package io.github.kubaj12.online_store.shared.time;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * The application's civil time zone, used for display and calendar-bound business values.
 *
 * <p>Persisted timestamps remain {@link Instant instants}; this type performs the explicit
 * conversion needed for Polish local dates, times, and order-number years.</p>
 */
public record ApplicationTimeZone(ZoneId zoneId) {

	public ApplicationTimeZone {
		Objects.requireNonNull(zoneId, "zoneId must not be null");
	}

	public ZonedDateTime at(Instant instant) {
		return Objects.requireNonNull(instant, "instant must not be null").atZone(zoneId);
	}

	public int yearAt(Instant instant) {
		return at(instant).getYear();
	}

}
