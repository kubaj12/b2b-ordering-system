package io.github.kubaj12.online_store.identityaccess.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical email value used at identity persistence boundaries. */
public final class NormalizedEmail {

	private static final Pattern SYNTAX = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$",
			Pattern.UNICODE_CHARACTER_CLASS);
	private final String value;

	private NormalizedEmail(String value) {
		this.value = value;
	}

	public static NormalizedEmail of(String input) {
		if (input == null) {
			throw new IllegalArgumentException("email must be provided");
		}
		String normalized = input.strip().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("email must be provided");
		}
		if (normalized.length() > 254 || containsWhitespace(normalized) || !SYNTAX.matcher(normalized).matches()) {
			throw new IllegalArgumentException("email must be a valid address");
		}
		return new NormalizedEmail(normalized);
	}

	private static boolean containsWhitespace(String value) {
		return value.codePoints().anyMatch(codePoint -> Character.isWhitespace(codePoint)
				|| Character.isSpaceChar(codePoint));
	}

	public String value() {
		return value;
	}

	@Override
	public boolean equals(Object other) {
		return this == other || other instanceof NormalizedEmail email && value.equals(email.value);
	}

	@Override
	public int hashCode() {
		return Objects.hash(value);
	}

	@Override
	public String toString() {
		return "NormalizedEmail[<configured>]";
	}

}
