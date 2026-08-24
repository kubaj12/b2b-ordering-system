/**
 * Framework-independent time primitives shared by modules.
 *
 * <p>Persisted points in time are represented as {@link java.time.Instant} values and mapped to
 * PostgreSQL {@code timestamp with time zone} columns. Feature code obtains the current instant
 * from an injected {@link java.time.Clock}; it never reads ambient system time. Conversion to the
 * configured application time zone happens only when a calendar value or display value is
 * required.</p>
 */
package io.github.kubaj12.online_store.shared.time;
