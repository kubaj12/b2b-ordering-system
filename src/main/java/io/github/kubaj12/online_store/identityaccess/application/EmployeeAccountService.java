package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.kubaj12.online_store.shared.auditing.*;

@Service
@Transactional
public class EmployeeAccountService {
    public record Directory(List<EmployeeAccountStore.Staff> staff, List<EmployeeAccountStore.Pending> invitations) {}
    private final EmployeeAccountStore store;
    private final AuditEventRecorder audit;
    private final Clock clock;
    public EmployeeAccountService(EmployeeAccountStore store, AuditEventRecorder audit, Clock clock) {
        this.store = store; this.audit = audit; this.clock = clock;
    }
    public Directory directory(UUID actor) {
        store.requireAdmin(actor);
        return new Directory(store.staff(), store.invitations(clock.instant()));
    }
    public void block(UUID actor, UUID target) { change(actor, target, IdentityAudit.Status.BLOCKED); }
    public void unblock(UUID actor, UUID target) { change(actor, target, IdentityAudit.Status.ACTIVE); }
    private void change(UUID actor, UUID target, IdentityAudit.Status next) {
        store.requireAdmin(actor);
        var previous = IdentityAudit.Status.valueOf(store.lockEmployee(target));
        if (previous == next) return;
        store.setStatus(target, next.name(), clock.instant());
        audit.record(IdentityAudit.STATUS_CHANGED.event(target, new AuditActor(actor), IdentityAudit.STATUS.change(previous, next)));
    }
}
