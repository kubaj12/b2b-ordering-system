package io.github.kubaj12.online_store.shared.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolishValidationTests {

	@Test
	void normalizesAndValidatesNipChecksum() {
		assertThat(PolishNip.of(" 526-025-09-95 ").value()).isEqualTo("5260250995");
		assertThat(PolishNip.of("123 456 32 18").value()).isEqualTo("1234563218");
		assertThat(PolishNip.isValid("5260250994")).isFalse();
		assertThat(PolishNip.isValid("526025099X")).isFalse();
		assertThatThrownBy(() -> PolishNip.of(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void validatesPostalCodesWithoutGuessingMalformedInput() {
		assertThat(PolishPostalCode.of(" 00-001 ").value()).isEqualTo("00-001");
		assertThat(PolishPostalCode.isValid("00001")).isFalse();
		assertThat(PolishPostalCode.isValid("0A-001")).isFalse();
	}

	@Test
	void normalizesCommonPolishPhonePresentations() {
		assertThat(PolishPhoneNumber.of("600 700 800").value()).isEqualTo("+48600700800");
		assertThat(PolishPhoneNumber.of("0048 600-700-800").value()).isEqualTo("+48600700800");
		assertThat(PolishPhoneNumber.isValid("+49 600 700 800")).isFalse();
		assertThat(PolishPhoneNumber.isValid("60070080")).isFalse();
	}

	@Test
	void constructsOnlyCompletePolandAddressesAndCanonicalizesOptionalUnit() {
		PolishAddress address = PolishAddress.of(" Długa ", " 2A ", "  ", "80-001", " Gdańsk ", "PL");

		assertThat(address.street()).isEqualTo("Długa");
		assertThat(address.unitNumber()).isNull();
		assertThatThrownBy(() -> PolishAddress.of("", "2A", null, "80-001", "Gdańsk", "PL"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PolishAddress.of("Długa", "2A", null, "80-001", "Gdańsk", "DE"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
