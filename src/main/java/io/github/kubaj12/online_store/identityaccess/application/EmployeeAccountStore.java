package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EmployeeAccountStore {
    record Staff(UUID id, String email, String role, String status, Instant lastLoginAt) {}
    record Pending(UUID id, String email, String status, Instant expiresAt) {}
    void requireAdmin(UUID actor);
    List<Staff> staff();
    List<Pending> invitations(Instant now);
}
