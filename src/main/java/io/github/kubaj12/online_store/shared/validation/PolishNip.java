package io.github.kubaj12.online_store.shared.validation;

import java.util.Objects;

/** A checksum-validated Polish tax identifier in its canonical ten-digit form. */
public final class PolishNip {

	private static final int[] WEIGHTS = { 6, 5, 7, 2, 3, 4, 5, 6, 7 };
	private final String value;

	private PolishNip(String value) {
		this.value = value;
	}

	public static PolishNip of(String input) {
		String normalized = normalize(input);
		if (!isValidCanonical(normalized)) {
			throw new IllegalArgumentException("NIP must have a valid Polish checksum");
		}
		return new PolishNip(normalized);
	}

	/** Removes the spaces and hyphens conventionally used when displaying a NIP. */
	public static String normalize(String input) {
		if (input == null) {
			return "";
		}
		StringBuilder result = new StringBuilder(input.length());
		input.strip().codePoints().forEach(codePoint -> {
			if (codePoint == '-' || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
				return;
			}
			result.appendCodePoint(codePoint);
		});
		return result.toString();
	}

	public static boolean isValid(String input) {
		return isValidCanonical(normalize(input));
	}

	private static boolean isValidCanonical(String value) {
		if (value.length() != 10 || !value.chars().allMatch(character -> character >= '0' && character <= '9')) {
			return false;
		}
		int sum = 0;
		for (int index = 0; index < WEIGHTS.length; index++) {
			sum += (value.charAt(index) - '0') * WEIGHTS[index];
		}
		int controlDigit = sum % 11;
		return controlDigit != 10 && controlDigit == value.charAt(9) - '0';
	}

	public String value() {
		return value;
	}

	@Override
	public boolean equals(Object other) {
		return this == other || other instanceof PolishNip nip && value.equals(nip.value);
	}

	@Override
	public int hashCode() {
		return Objects.hash(value);
	}

	@Override
	public String toString() {
		return "PolishNip[***" + value.substring(7) + "]";
	}
}
