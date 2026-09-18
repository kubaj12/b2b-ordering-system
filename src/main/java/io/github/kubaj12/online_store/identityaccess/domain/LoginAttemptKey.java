package io.github.kubaj12.online_store.identityaccess.domain;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** Redacted, canonical key for a login identity and trusted request source. */
public final class LoginAttemptKey {

	private final byte[] identityHash;
	private final String sourceAddress;

	private LoginAttemptKey(byte[] identityHash, String sourceAddress) {
		this.identityHash = identityHash.clone();
		this.sourceAddress = sourceAddress;
	}

	public static LoginAttemptKey of(String attemptedIdentity, String sourceAddress) {
		String source = canonicalSource(sourceAddress);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return new LoginAttemptKey(digest.digest(NormalizedEmail.normalizeAttempt(attemptedIdentity)
					.getBytes(StandardCharsets.UTF_8)), source);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String canonicalSource(String input) {
		if (input == null || input.isBlank()) {
			throw new IllegalArgumentException("login source must be a literal IP address");
		}
		String literal = input.strip();
		int scope = literal.indexOf('%');
		if (scope >= 0) {
			literal = literal.substring(0, scope);
		}
		if (literal.indexOf('/') >= 0 || literal.indexOf('[') >= 0 || literal.indexOf(']') >= 0) {
			throw new IllegalArgumentException("login source must be a literal IP address");
		}
		final InetAddress address;
		try {
			address = InetAddress.ofLiteral(literal);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("login source must be a literal IP address");
		}
		byte[] bytes = address.getAddress();
		if (bytes.length == 16 && isIpv4Mapped(bytes)) {
			byte[] ipv4 = Arrays.copyOfRange(bytes, 12, 16);
			return (ipv4[0] & 255) + "." + (ipv4[1] & 255) + "." + (ipv4[2] & 255) + "." + (ipv4[3] & 255);
		}
		return address.getHostAddress().replace("%", "");
	}

	private static boolean isIpv4Mapped(byte[] bytes) {
		for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
		return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
	}

	public byte[] identityHash() { return identityHash.clone(); }
	public String sourceAddress() { return sourceAddress; }

	@Override public boolean equals(Object other) {
		return this == other || other instanceof LoginAttemptKey key
				&& Arrays.equals(identityHash, key.identityHash) && sourceAddress.equals(key.sourceAddress);
	}
	@Override public int hashCode() { return 31 * Arrays.hashCode(identityHash) + sourceAddress.hashCode(); }
	@Override public String toString() { return "LoginAttemptKey[<redacted>]"; }
}
