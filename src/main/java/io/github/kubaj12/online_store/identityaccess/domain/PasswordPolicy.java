package io.github.kubaj12.online_store.identityaccess.domain;

import java.nio.charset.StandardCharsets;

/** Password creation policy shared by configuration and identity application services. */
public final class PasswordPolicy {

	public static final int MINIMUM_CHARACTERS = 12;
	public static final int MAXIMUM_UTF8_BYTES = 72;

	private PasswordPolicy() {
	}

	public static boolean hasMinimumCharacters(String password) {
		return password != null && !password.isBlank()
				&& password.codePointCount(0, password.length()) >= MINIMUM_CHARACTERS;
	}

	public static boolean hasValidUtf8Length(String password) {
		return password != null && hasWellFormedUnicode(password)
				&& password.getBytes(StandardCharsets.UTF_8).length <= MAXIMUM_UTF8_BYTES;
	}

	public static void validate(String password) {
		if (password == null || password.isBlank()) {
			throw new IllegalArgumentException("password must be provided and nonblank");
		}
		if (!hasWellFormedUnicode(password)) {
			throw new IllegalArgumentException("password must contain valid Unicode text");
		}
		if (password.codePointCount(0, password.length()) < MINIMUM_CHARACTERS) {
			throw new IllegalArgumentException("password must contain at least 12 characters");
		}
		if (password.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_UTF8_BYTES) {
			throw new IllegalArgumentException("password must contain at most 72 UTF-8 bytes");
		}
	}

	private static boolean hasWellFormedUnicode(String password) {
		for (int index = 0; index < password.length(); index++) {
			char character = password.charAt(index);
			if (Character.isHighSurrogate(character)) {
				if (index + 1 >= password.length() || !Character.isLowSurrogate(password.charAt(++index))) {
					return false;
				}
			}
			else if (Character.isLowSurrogate(character)) {
				return false;
			}
		}
		return true;
	}

}
