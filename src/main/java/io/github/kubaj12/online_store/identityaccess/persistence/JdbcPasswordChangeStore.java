package io.github.kubaj12.online_store.identityaccess.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.github.kubaj12.online_store.identityaccess.application.PasswordChangeStore;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcPasswordChangeStore implements PasswordChangeStore {
    private final JdbcTemplate jdbc;

    public JdbcPasswordChangeStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<LockedAccount> lockActivePassword(UUID accountId) {
        return jdbc.query("SELECT password_hash, security_version FROM identity_user WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
                (rs, row) -> new LockedAccount(rs.getString("password_hash"), rs.getLong("security_version")),
                accountId).stream().findFirst();
    }

    @Override
    public void replacePassword(UUID accountId, String passwordHash, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        int updated = jdbc.update("UPDATE identity_user SET password_hash = ?, security_version = security_version + 1, updated_at = ? WHERE id = ? AND status = 'ACTIVE'",
                passwordHash, timestamp, accountId);
        if (updated != 1) throw new IllegalStateException("Password change requires one active account");
        jdbc.update("UPDATE identity_password_reset_token SET revoked_at = ?, updated_at = ? WHERE user_id = ? AND consumed_at IS NULL AND revoked_at IS NULL",
                timestamp, timestamp, accountId);
        jdbc.update("UPDATE identity_session SET revoked_at = ?, updated_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                timestamp, timestamp, accountId);
    }
}
