package io.github.kubaj12.online_store.notifications.application;

import java.util.Objects;

/** Immutable rendered mail. Its body is deliberately redacted from {@link #toString()}. */
public record OutgoingMail(
		String recipient,
		String subject,
		String plainTextBody,
		String htmlBody
) {

	public OutgoingMail {
		recipient = requireText(recipient, "recipient");
		subject = requireText(subject, "subject");
		plainTextBody = Objects.requireNonNull(plainTextBody, "plainTextBody must not be null");
		htmlBody = Objects.requireNonNull(htmlBody, "htmlBody must not be null");
		if (plainTextBody.isBlank() && htmlBody.isBlank()) {
			throw new IllegalArgumentException("at least one mail body must not be blank");
		}
	}

	@Override
	public String toString() {
		return "OutgoingMail[recipient=%s, subject=%s, plainTextBody=<redacted>, htmlBody=<redacted>]"
				.formatted(recipient, subject);
	}

	private static String requireText(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}

}
