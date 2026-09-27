package io.github.kubaj12.online_store.customers.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import io.github.kubaj12.online_store.identityaccess.application.InvitationException;
import io.github.kubaj12.online_store.identityaccess.application.InvitationLifecycleHandler;

/** Customer-owned invitation work, intentionally independent of InvitationService to avoid a bean cycle. */
@Component
public final class CustomerInvitationLifecycleHandler implements InvitationLifecycleHandler {
    private final CustomerAdministrationStore store;

    public CustomerInvitationLifecycleHandler(CustomerAdministrationStore store) {
        this.store = store;
    }

    @Override
    public void onResend(UUID oldId, UUID newId, String role, Instant now) {
        if ("CUSTOMER".equals(role)) {
            try {
                store.requireInvitationNipAvailable(oldId, now);
                store.transferInvitationPayload(oldId, newId, now);
            } catch (DataIntegrityViolationException exception) {
                throw new InvitationException();
            }
        }
    }

    @Override
    public void onAccept(UUID invitationId, UUID accountId, String role, Instant now) {
        if ("CUSTOMER".equals(role)) {
            try {
                store.requireInvitationNipAvailable(invitationId, now);
                store.createProfileFromInvitation(invitationId, accountId, now);
            } catch (DataIntegrityViolationException exception) {
                // Keep persistence details out of the anonymous activation endpoint. Throwing
                // also rolls back the account insert performed earlier in the same transaction.
                throw new InvitationException();
            }
        }
    }
}
