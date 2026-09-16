package io.github.kubaj12.online_store.identityaccess.domain;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTests {

	@Test
	void enforcesUnicodeCodePointMinimumAndPreservesValidWhitespace() {
		assertThatThrownBy(() -> PasswordPolicy.validate("12345678901"))
				.isInstanceOf(IllegalArgumentException.class);
		String password = "  1234567890  ";
		PasswordPolicy.validate(password);
		assertThat(password.codePointCount(0, password.length())).isEqualTo(14);
		assertThat("😀".repeat(11)).satisfies(value ->
			assertThatThrownBy(() -> PasswordPolicy.validate(value)).isInstanceOf(IllegalArgumentException.class));
		PasswordPolicy.validate("😀".repeat(12));
	}

	@Test
	void enforcesWellFormedUnicodeAndUtf8ByteLimit() {
		assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(11) + "\uD800"))
				.isInstanceOf(IllegalArgumentException.class);
		String exactly72 = "ą".repeat(36);
		assertThat(exactly72.getBytes(StandardCharsets.UTF_8)).hasSize(72);
		PasswordPolicy.validate(exactly72);
		assertThatThrownBy(() -> PasswordPolicy.validate("ą".repeat(36) + "ą"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("72 UTF-8 bytes");
	}

	@Test
	void rejectsNullEmptyAndBlankPasswords() {
		assertThatThrownBy(() -> PasswordPolicy.validate(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PasswordPolicy.validate("")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PasswordPolicy.validate("            ")).isInstanceOf(IllegalArgumentException.class);
	}

}
