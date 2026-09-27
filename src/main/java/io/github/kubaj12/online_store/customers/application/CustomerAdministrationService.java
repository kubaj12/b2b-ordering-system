package io.github.kubaj12.online_store.customers.application;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.kubaj12.online_store.identityaccess.application.*;

@Service
public class CustomerAdministrationService {
    public record Directory(List<CustomerAdministrationStore.Summary> customers,
            List<CustomerAdministrationStore.Pending> invitations) { }
    private final CustomerAdministrationStore store;
    private final InvitationService invitations;
    private final AccountStatusService statuses;
    private final Clock clock;
    public CustomerAdministrationService(CustomerAdministrationStore store, InvitationService invitations,
            AccountStatusService statuses, Clock clock) {
        this.store = store; this.invitations = invitations; this.statuses = statuses; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public Directory directory() { return new Directory(store.customers(), store.pendingInvitations()); }
    @Transactional(readOnly = true)
    public CustomerAdministrationStore.Detail detail(UUID id) {
        return store.detail(id).orElseThrow(CustomerNotFoundException::new);
    }
    public UUID invite(String email, CustomerProfileData profile, UUID actor) {
        if (profile == null) throw new IllegalArgumentException("complete customer profile required");
        var now = now();
        try {
            return invitations.inviteCustomer(email, actor, id -> {
                store.requireNipAvailable(profile.nip().value(), null, now);
                store.addInvitationPayload(id, profile, now);
            }).id();
        } catch (InvitationException | DataIntegrityViolationException exception) {
            throw new CustomerInvitationConflictException();
        }
    }
    @Transactional
    public void update(UUID id, CustomerProfileData profile) {
        if (store.detail(id).isEmpty()) throw new CustomerNotFoundException();
        store.requireNipAvailable(profile.nip().value(), id, now());
        store.update(id, profile, now());
    }
    public void block(UUID actor, UUID customer) { statuses.blockCustomer(actor, customer); }
    public void unblock(UUID actor, UUID customer) { statuses.unblockCustomer(actor, customer); }
    private java.time.Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
