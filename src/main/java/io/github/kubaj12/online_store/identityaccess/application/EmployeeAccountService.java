package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class EmployeeAccountService {
    public record Directory(List<EmployeeAccountStore.Staff> staff, List<EmployeeAccountStore.Pending> invitations) {}
    private final EmployeeAccountStore store;
    private final AccountStatusService statuses;
    private final Clock clock;
    public EmployeeAccountService(EmployeeAccountStore store, AccountStatusService statuses, Clock clock) {
        this.store = store; this.statuses = statuses; this.clock = clock;
    }
    public Directory directory(UUID actor) {
        store.requireAdmin(actor);
        return new Directory(store.staff(), store.invitations(clock.instant()));
    }
    public void block(UUID actor, UUID target) { statuses.changeEmployee(actor, target, IdentityAudit.Status.BLOCKED); }
    public void unblock(UUID actor, UUID target) { statuses.changeEmployee(actor, target, IdentityAudit.Status.ACTIVE); }
}
