package io.github.kubaj12.online_store.shared.web.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolishFormValidationTests {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void appliesReusableConstraintsAtTheFormBoundary() {
		var valid = new CustomerForm("526-025-09-95", "+48 600 700 800",
				"Prosta", "1", null, "00-001", "Warszawa", "PL");
		var invalid = new CustomerForm("5260250994", "+49 600 700 800",
				"", "1", null, "00001", "Warszawa", "DE");

		assertThat(validator.validate(valid)).isEmpty();
		assertThat(validator.validate(invalid)).hasSize(4);
	}

	@CompletePolishAddress
	private record CustomerForm(
			@ValidPolishNip String nip,
			@ValidPolishPhone String phone,
			String street,
			String buildingNumber,
			String unitNumber,
			@ValidPolishPostalCode String postalCode,
			String city,
			String country
	) implements PolishAddressFields { }
}
