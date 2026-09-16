package io.github.kubaj12.online_store.identityaccess.domain;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedEmailTests {

	@Test
	void canonicalizesCaseAndSurroundingWhitespaceIndependentlyOfLocale() {
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr"));
			assertThat(NormalizedEmail.of("  ADMIN@Example.COM "))
				.isEqualTo(NormalizedEmail.of("admin@example.com"))
				.extracting(NormalizedEmail::value).isEqualTo("admin@example.com");
		}
		finally {
			Locale.setDefault(previous);
		}
	}

	@Test
	void rejectsMissingWhitespaceAndMalformedAddressesWithoutEchoingInput() {
		String submitted = "bad address@example.com";
		assertThatThrownBy(() -> NormalizedEmail.of(submitted))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageNotContaining(submitted);
		assertThatThrownBy(() -> NormalizedEmail.of("missing-at.example.com"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> NormalizedEmail.of("x\u2003@example.com"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNullAndAddressesOver254Characters() {
		assertThatThrownBy(() -> NormalizedEmail.of(null)).isInstanceOf(IllegalArgumentException.class);
		String tooLong = "a".repeat(244) + "@example.com";
		assertThat(tooLong.length()).isGreaterThan(254);
		assertThatThrownBy(() -> NormalizedEmail.of(tooLong)).isInstanceOf(IllegalArgumentException.class);
	}

}
