package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

public interface AccountStatusStore {
    record Account(UUID id, String role, String status) {}
    /** Locks actor and target in UUID order, including inactive/unauthorized rows. */
    java.util.List<Account> lockAccounts(UUID actor, UUID target);
    void setStatus(UUID target, String status, Instant now);
    void revokeSessions(UUID target, Instant now);
}
