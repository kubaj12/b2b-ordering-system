package io.github.kubaj12.online_store.shared.validation;

/** Complete Poland-only street address accepted at application-service boundaries. */
public record PolishAddress(
		String street,
		String buildingNumber,
		String unitNumber,
		PolishPostalCode postalCode,
		String city,
		String country
) {
	public PolishAddress {
		street = required(street, 255, "street");
		buildingNumber = required(buildingNumber, 32, "building number");
		unitNumber = optional(unitNumber, 32, "unit number");
		if (postalCode == null) {
			throw new IllegalArgumentException("postal code must be provided");
		}
		city = required(city, 120, "city");
		country = country == null ? "" : country.strip();
		if (!"PL".equals(country)) {
			throw new IllegalArgumentException("country must be PL");
		}
	}

	public static PolishAddress of(String street, String buildingNumber, String unitNumber,
			String postalCode, String city, String country) {
		return new PolishAddress(street, buildingNumber, unitNumber, PolishPostalCode.of(postalCode), city, country);
	}

	private static String required(String input, int maximumLength, String field) {
		String value = input == null ? "" : input.strip();
		if (value.isEmpty()) {
			throw new IllegalArgumentException(field + " must be provided");
		}
		if (value.length() > maximumLength) {
			throw new IllegalArgumentException(field + " is too long");
		}
		return value;
	}

	private static String optional(String input, int maximumLength, String field) {
		if (input == null) {
			return null;
		}
		String value = input.strip();
		if (value.isEmpty()) {
			return null;
		}
		if (value.length() > maximumLength) {
			throw new IllegalArgumentException(field + " is too long");
		}
		return value;
	}
}
