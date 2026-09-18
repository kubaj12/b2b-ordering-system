package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import io.github.kubaj12.online_store.identityaccess.domain.InvitationRole;

/** All mutations and email locking require the same transaction. */
public interface InvitationStore {
    record Invitation(UUID id, String email, InvitationRole role, String status, Instant expiresAt) {}
    Optional<Invitation> findByHash(byte[] hash);
    Optional<Invitation> findById(UUID id);
    void lockEmail(String email);
    void authorizeActor(UUID actor, InvitationRole role);
    boolean accountExists(String email);
    void expirePending(String email, Instant now);
    boolean pendingExists(String email);
    void revoke(UUID id, Instant now);
    void insert(UUID id, String email, InvitationRole role, byte[] hash, UUID actor, Instant now, Instant expiresAt);
    boolean createAccount(UUID id, String email, InvitationRole role, String passwordHash, Instant now);
    void accept(UUID invitationId, UUID accountId, Instant now);
}
