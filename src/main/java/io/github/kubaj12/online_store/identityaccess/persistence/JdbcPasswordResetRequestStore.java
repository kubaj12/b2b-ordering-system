package io.github.kubaj12.online_store.identityaccess.persistence;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import io.github.kubaj12.online_store.identityaccess.application.PasswordResetRequestStore;
@Repository
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
public class JdbcPasswordResetRequestStore implements PasswordResetRequestStore {
    private final JdbcTemplate jdbc;
    public JdbcPasswordResetRequestStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<UUID> lockActiveAccount(String email) {
        return jdbc.query("SELECT id FROM identity_user WHERE email = ? AND status = 'ACTIVE' FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), email).stream().findFirst();
    }
    public Optional<UUID> findUser(byte[] hash) {
        return jdbc.query("SELECT user_id FROM identity_password_reset_token WHERE token_hash = ?",
                (rs, row) -> rs.getObject("user_id", UUID.class), hash).stream().findFirst();
    }
    public boolean lockActiveUser(UUID user) {
        // All reset writers lock the account before touching tokens, so request/consume share lock ordering.
        var active = jdbc.query("SELECT id FROM identity_user WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), user);
        return !active.isEmpty();
    }
    public boolean eligible(UUID user, byte[] hash, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM identity_password_reset_token WHERE user_id = ? AND token_hash = ? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > ?)",
                Boolean.class, user, hash, Timestamp.from(now)));
    }
    public boolean replacePassword(UUID user, byte[] hash, String passwordHash, Instant now) {
        int consumed = jdbc.update("UPDATE identity_password_reset_token SET consumed_at = ?, updated_at = ? WHERE user_id = ? AND token_hash = ? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > ?",
                Timestamp.from(now), Timestamp.from(now), user, hash, Timestamp.from(now));
        if (consumed != 1) return false;
        int replaced = jdbc.update("UPDATE identity_user SET password_hash = ?, security_version = security_version + 1, updated_at = ? WHERE id = ? AND status = 'ACTIVE'",
                passwordHash, Timestamp.from(now), user);
        // Throw so the surrounding transaction rolls back consumption as well as every other write.
        if (replaced != 1) throw new IllegalStateException("Password replacement requires one active account");
        jdbc.update("UPDATE identity_password_reset_token SET revoked_at = ?, updated_at = ? WHERE user_id = ? AND consumed_at IS NULL AND revoked_at IS NULL",
                Timestamp.from(now), Timestamp.from(now), user);
        jdbc.update("UPDATE identity_session SET revoked_at = ?, updated_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                Timestamp.from(now), Timestamp.from(now), user);
        return true;
    }
    public void replace(UUID user, byte[] hash, Instant now, Instant expires) {
        jdbc.update("UPDATE identity_password_reset_token SET revoked_at = ?, updated_at = ? WHERE user_id = ? AND consumed_at IS NULL AND revoked_at IS NULL",
                Timestamp.from(now), Timestamp.from(now), user);
        jdbc.update("INSERT INTO identity_password_reset_token (id, user_id, token_hash, created_at, updated_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), user, hash, Timestamp.from(now), Timestamp.from(now), Timestamp.from(expires));
    }
}
