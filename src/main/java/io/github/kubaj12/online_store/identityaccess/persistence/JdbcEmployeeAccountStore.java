package io.github.kubaj12.online_store.identityaccess.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import io.github.kubaj12.online_store.identityaccess.application.EmployeeAccountStore;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcEmployeeAccountStore implements EmployeeAccountStore {
    private final JdbcTemplate jdbc;
    public JdbcEmployeeAccountStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void requireAdmin(UUID actor) {
        if (jdbc.query("SELECT id FROM identity_user WHERE id = ? AND role = 'ADMIN' AND status = 'ACTIVE' FOR SHARE",
                (rs, row) -> rs.getObject(1, UUID.class), actor).isEmpty()) throw new AccessDeniedException("administrator required");
    }
    public List<Staff> staff() {
        return jdbc.query("SELECT id, email, role, status, last_login_at FROM identity_user WHERE role IN ('ADMIN', 'EMPLOYEE') ORDER BY email",
            (rs, row) -> new Staff(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("role"), rs.getString("status"),
                rs.getObject("last_login_at", OffsetDateTime.class) == null ? null : rs.getObject("last_login_at", OffsetDateTime.class).toInstant()));
    }
    public List<Pending> invitations(Instant now) {
        return jdbc.query("""
            SELECT DISTINCT ON (email) id, email, CASE WHEN expires_at <= ? THEN 'EXPIRED' ELSE status END AS status, expires_at
            FROM identity_invitation i WHERE role = 'EMPLOYEE' AND status IN ('PENDING', 'EXPIRED')
            AND NOT EXISTS (SELECT 1 FROM identity_user u WHERE u.email = i.email)
            ORDER BY email, CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, created_at DESC, id DESC
            """, (rs, row) -> new Pending(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("status"), rs.getObject("expires_at", OffsetDateTime.class).toInstant()), now.atOffset(ZoneOffset.UTC));
    }
    public String lockEmployee(UUID target) {
        return jdbc.query("SELECT status FROM identity_user WHERE id = ? AND role = 'EMPLOYEE' FOR UPDATE",
            (rs, row) -> rs.getString(1), target).stream().findFirst().orElseThrow(() -> new AccessDeniedException("employee target required"));
    }
    public void setStatus(UUID target, String status, Instant now) {
        jdbc.update("UPDATE identity_user SET status = ?, security_version = security_version + CASE WHEN ? = 'BLOCKED' THEN 1 ELSE 0 END, updated_at = ? WHERE id = ? AND role = 'EMPLOYEE'",
            status, status, now.atOffset(ZoneOffset.UTC), target);
    }
}
