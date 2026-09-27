package io.github.kubaj12.online_store.customers.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerAdministrationStore {
    record Summary(UUID accountId, String email, String status, String companyName, String nip) { }
    record Detail(UUID accountId, String email, String status, CustomerProfileData profile,
            Instant createdAt, Instant updatedAt) { }
    record Pending(UUID invitationId, String email, String status, CustomerProfileData profile,
            Instant expiresAt) { }
    List<Summary> customers();
    List<Pending> pendingInvitations();
    Optional<Detail> detail(UUID accountId);
    void requireNipAvailable(String nip, UUID editedAccountId, Instant now);
    void requireInvitationNipAvailable(UUID invitationId, Instant now);
    void addInvitationPayload(UUID invitationId, CustomerProfileData profile, Instant now);
    void transferInvitationPayload(UUID previousId, UUID replacementId, Instant now);
    void createProfileFromInvitation(UUID invitationId, UUID accountId, Instant now);
    void update(UUID accountId, CustomerProfileData profile, Instant now);
}
