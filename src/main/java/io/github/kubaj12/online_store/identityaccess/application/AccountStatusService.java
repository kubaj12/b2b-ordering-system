package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.kubaj12.online_store.shared.auditing.*;

/** Shared authorization and atomic status/security/audit boundary for customer and staff administration. */
@Service
@Transactional
public class AccountStatusService {
    private enum TargetScope { ANY, CUSTOMER, EMPLOYEE }
    private final AccountStatusStore store;
    private final AuditEventRecorder audit;
    private final Clock clock;
    public AccountStatusService(AccountStatusStore store, AuditEventRecorder audit, Clock clock) {
        this.store = store; this.audit = audit; this.clock = clock;
    }
    public void block(UUID actor, UUID target) { change(actor, target, IdentityAudit.Status.BLOCKED, TargetScope.ANY); }
    public void unblock(UUID actor, UUID target) { change(actor, target, IdentityAudit.Status.ACTIVE, TargetScope.ANY); }
    /** Customer-directory actions must not change a staff account through a customer URL. */
    public void blockCustomer(UUID actor, UUID target) { change(actor, target, IdentityAudit.Status.BLOCKED, TargetScope.CUSTOMER); }
    public void unblockCustomer(UUID actor, UUID target) { change(actor, target, IdentityAudit.Status.ACTIVE, TargetScope.CUSTOMER); }
    /** Employee-directory actions retain their narrower employee-only target boundary. */
    public void changeEmployee(UUID actor, UUID target, IdentityAudit.Status next) { change(actor, target, next, TargetScope.EMPLOYEE); }
    private void change(UUID actorId, UUID targetId, IdentityAudit.Status next, TargetScope scope) {
        if (actorId == null || targetId == null) throw new AccessDeniedException("account status change denied");
        var accounts = store.lockAccounts(actorId, targetId);
        var actor = accounts.stream().filter(a -> a.id().equals(actorId)).findFirst()
            .orElseThrow(() -> new AccessDeniedException("account status change denied"));
        var target = accounts.stream().filter(a -> a.id().equals(targetId)).findFirst()
            .orElseThrow(() -> new AccessDeniedException("account status change denied"));
        boolean permitted = "ACTIVE".equals(actor.status()) && (
            "ADMIN".equals(actor.role()) && ("CUSTOMER".equals(target.role()) || "EMPLOYEE".equals(target.role()))
            || "EMPLOYEE".equals(actor.role()) && "CUSTOMER".equals(target.role()));
        if (!permitted
                || (scope == TargetScope.CUSTOMER && !"CUSTOMER".equals(target.role()))
                || (scope == TargetScope.EMPLOYEE && (!"ADMIN".equals(actor.role()) || !"EMPLOYEE".equals(target.role()))))
            throw new AccessDeniedException("account status change denied");
        var previous = IdentityAudit.Status.valueOf(target.status());
        if (previous == next) return;
        var now = clock.instant();
        store.setStatus(targetId, next.name(), now);
        if (next == IdentityAudit.Status.BLOCKED) store.revokeSessions(targetId, now);
        audit.record(IdentityAudit.STATUS_CHANGED.event(targetId, new AuditActor(actorId), IdentityAudit.STATUS.change(previous, next)));
    }
}
