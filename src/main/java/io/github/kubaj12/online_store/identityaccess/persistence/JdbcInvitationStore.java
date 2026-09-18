package io.github.kubaj12.online_store.identityaccess.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.github.kubaj12.online_store.identityaccess.application.InvitationStore;
import io.github.kubaj12.online_store.identityaccess.domain.InvitationRole;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcInvitationStore implements InvitationStore {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Invitation> MAPPER = (rs, row) -> new Invitation(
            rs.getObject("id", UUID.class), rs.getString("email"), InvitationRole.valueOf(rs.getString("role")),
            rs.getString("status"), rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    public JdbcInvitationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<Invitation> findByHash(byte[] hash) {
        return jdbc.query("SELECT * FROM identity_invitation WHERE token_hash = ?", MAPPER, hash).stream().findFirst();
    }
    public Optional<Invitation> findById(UUID id) {
        return jdbc.query("SELECT * FROM identity_invitation WHERE id = ?", MAPPER, id).stream().findFirst();
    }
    public void lockEmail(String email) {
        // Transaction-scoped, also works before any invitation/account row exists.
        // Hash collisions only serialize unrelated emails; they cannot weaken exclusion.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 734921))", rs -> { }, email);
    }
    public void authorizeActor(UUID actor, InvitationRole role) {
        var roles = jdbc.query("SELECT role FROM identity_user WHERE id = ? AND status = 'ACTIVE' FOR SHARE",
                (rs, row) -> rs.getString(1), actor);
        if (roles.isEmpty() || !(roles.getFirst().equals("ADMIN")
                || (role == InvitationRole.CUSTOMER && roles.getFirst().equals("EMPLOYEE")))) {
            throw new AccessDeniedException("invitation action forbidden");
        }
    }
    public boolean accountExists(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM identity_user WHERE email = ?)", Boolean.class, email));
    }
    public boolean pendingExists(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM identity_invitation WHERE email = ? AND status = 'PENDING')", Boolean.class, email));
    }
    public void expirePending(String email, Instant now) {
        jdbc.update("UPDATE identity_invitation SET status = 'EXPIRED', updated_at = ? WHERE email = ? AND status = 'PENDING' AND expires_at <= ?",
                utc(now), email, utc(now));
    }
    public void revoke(UUID id, Instant now) {
        requireOne(jdbc.update("UPDATE identity_invitation SET status = 'REVOKED', revoked_at = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'",
                utc(now), utc(now), id));
    }
    public void insert(UUID id, String email, InvitationRole role, byte[] hash, UUID actor, Instant now, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO identity_invitation (id, email, role, status, token_hash, invited_by_user_id, created_at, updated_at, expires_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?)
                """, id, email, role.name(), hash, actor, utc(now), utc(now), utc(expiresAt));
    }
    public boolean createAccount(UUID id, String email, InvitationRole role, String passwordHash, Instant now) {
        return jdbc.update("""
                INSERT INTO identity_user (id, email, password_hash, role, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT ON CONSTRAINT identity_user_email_uq DO NOTHING
                """, id, email, passwordHash, role.name(), utc(now), utc(now)) == 1;
    }
    public void accept(UUID invitationId, UUID accountId, Instant now) {
        requireOne(jdbc.update("""
                UPDATE identity_invitation SET status = 'ACCEPTED', accepted_user_id = ?, accepted_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PENDING' AND expires_at > ?
                """, accountId, utc(now), utc(now), invitationId, utc(now)));
    }
    private static void requireOne(int count) {
        if (count != 1) throw new IllegalStateException("invitation transition failed");
    }
    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
