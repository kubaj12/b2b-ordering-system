package io.github.kubaj12.online_store.identityaccess.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** 256-bit random bearer secret; only its SHA-256 digest crosses persistence boundaries. */
public final class InvitationToken {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String value;
    private InvitationToken(String value) { this.value = value; }
    public static InvitationToken generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new InvitationToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
    public static InvitationToken parse(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)).equals(value)) {
            throw new IllegalArgumentException("invalid invitation token");
        }
        return new InvitationToken(value);
    }
    public String value() { return value; }
    public byte[] hash() {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    @Override public String toString() { return "InvitationToken[<redacted>]"; }
}
