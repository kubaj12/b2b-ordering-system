package io.github.kubaj12.online_store.notifications.application;

import java.util.Objects;

/** A classified delivery failure that lets application code decide whether a retry is safe. */
public final class MailDeliveryException extends RuntimeException {

	public enum Kind {
		TEMPORARY,
		PERMANENT
	}

	private final Kind kind;

	private MailDeliveryException(Kind kind, Throwable cause) {
		super("Mail delivery failed with a " + kind.name().toLowerCase() + " failure", cause);
		this.kind = Objects.requireNonNull(kind, "kind must not be null");
	}

	public static MailDeliveryException temporary(Throwable cause) {
		return new MailDeliveryException(Kind.TEMPORARY, cause);
	}

	public static MailDeliveryException permanent(Throwable cause) {
		return new MailDeliveryException(Kind.PERMANENT, cause);
	}

	public Kind kind() {
		return kind;
	}

}
