package io.github.kubaj12.online_store.shared.validation;

import java.util.Objects;
import java.util.regex.Pattern;

/** A Polish postal code in the canonical NN-NNN form. */
public record PolishPostalCode(String value) {

	private static final Pattern FORMAT = Pattern.compile("^[0-9]{2}-[0-9]{3}$");

	public PolishPostalCode {
		value = value == null ? "" : value.strip();
		if (!FORMAT.matcher(value).matches()) {
			throw new IllegalArgumentException("postal code must use the NN-NNN format");
		}
	}

	public static PolishPostalCode of(String input) {
		return new PolishPostalCode(input);
	}

	public static boolean isValid(String input) {
		return input != null && FORMAT.matcher(input.strip()).matches();
	}

	@Override
	public String toString() {
		return Objects.requireNonNull(value);
	}
}
