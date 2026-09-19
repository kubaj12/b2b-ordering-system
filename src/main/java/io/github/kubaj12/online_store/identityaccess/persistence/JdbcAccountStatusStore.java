package io.github.kubaj12.online_store.identityaccess.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import io.github.kubaj12.online_store.identityaccess.application.AccountStatusStore;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcAccountStatusStore implements AccountStatusStore {
    private final JdbcTemplate jdbc;
    public JdbcAccountStatusStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Account> lockAccounts(UUID actor, UUID target) {
        return jdbc.query("SELECT id, role, status FROM identity_user WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
            (rs, row) -> new Account(rs.getObject("id", UUID.class), rs.getString("role"), rs.getString("status")), actor, target);
    }
    public void setStatus(UUID target, String status, Instant now) {
        jdbc.update("""
            UPDATE identity_user SET status = ?,
            security_version = security_version + CASE WHEN ? = 'BLOCKED' THEN 1 ELSE 0 END,
            updated_at = GREATEST(updated_at, ?) WHERE id = ?
            """, status, status, now.atOffset(ZoneOffset.UTC), target);
    }
    public void revokeSessions(UUID target, Instant now) {
        jdbc.update("""
            UPDATE identity_session SET revoked_at = GREATEST(updated_at, ?),
            updated_at = GREATEST(updated_at, ?) WHERE user_id = ? AND revoked_at IS NULL
            """, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC), target);
    }
}
