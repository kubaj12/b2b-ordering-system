package io.github.kubaj12.online_store.identityaccess.application;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
public interface PasswordResetRequestStore {
    Optional<UUID> lockActiveAccount(String email);
    Optional<UUID> findUser(byte[] hash);
    boolean lockActiveUser(UUID user);
    boolean eligible(UUID user, byte[] hash, Instant now);
    /** Caller must hold the account lock; conditional update rechecks expiry after hashing. */
    boolean replacePassword(UUID user, byte[] hash, String passwordHash, Instant now);
    void replace(UUID user, byte[] hash, Instant now, Instant expires);
}
