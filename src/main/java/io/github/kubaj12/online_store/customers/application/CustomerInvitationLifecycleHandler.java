package io.github.kubaj12.online_store.customers.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
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
        if ("CUSTOMER".equals(role)) store.transferInvitationPayload(oldId, newId, now);
    }

    @Override
    public void onAccept(UUID invitationId, UUID accountId, String role, Instant now) {
        if ("CUSTOMER".equals(role)) store.createProfileFromInvitation(invitationId, accountId, now);
    }
}
