package io.github.kubaj12.online_store.shared.validation;

import java.util.Objects;

/** A Polish telephone number stored canonically as +48 followed by nine digits. */
public record PolishPhoneNumber(String value) {

	public PolishPhoneNumber {
		value = normalize(value);
		if (!isCanonical(value)) {
			throw new IllegalArgumentException("phone must be a nine-digit Polish number");
		}
	}

	public static PolishPhoneNumber of(String input) {
		return new PolishPhoneNumber(input);
	}

	public static boolean isValid(String input) {
		return isCanonical(normalize(input));
	}

	public static String normalize(String input) {
		if (input == null) {
			return "";
		}
		String compact = input.strip().replace(" ", "").replace("-", "").replace("(", "").replace(")", "");
		if (compact.startsWith("0048")) {
			compact = "+48" + compact.substring(4);
		} else if (!compact.startsWith("+")) {
			compact = "+48" + compact;
		}
		return compact;
	}

	private static boolean isCanonical(String value) {
		return value.length() == 12 && value.startsWith("+48")
				&& value.substring(3).chars().allMatch(character -> character >= '0' && character <= '9');
	}

	@Override
	public String toString() {
		return Objects.requireNonNull(value);
	}
}
