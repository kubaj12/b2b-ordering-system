package io.github.kubaj12.online_store.identityaccess.application;

import java.util.UUID;
import io.github.kubaj12.online_store.shared.auditing.*;

public final class IdentityAudit {
    private IdentityAudit() {}
    public enum Status { ACTIVE, BLOCKED }
    public static final AuditField<Status> STATUS = AuditField.enumeration("status", Status.class);
    public static final AuditEventType<UUID> STATUS_CHANGED = new AuditEventType<>("identity.account.status_changed", AuditTargetType.uuid("identity.user"), STATUS);
    public static final AuditEventType<UUID> INVITED = new AuditEventType<>("identity.invitation.issued", AuditTargetType.uuid("identity.invitation"));
    public static final AuditEventType<UUID> REVOKED = new AuditEventType<>("identity.invitation.revoked", AuditTargetType.uuid("identity.invitation"));
    public static final AuditEventType<UUID> ACCEPTED = new AuditEventType<>("identity.invitation.accepted", AuditTargetType.uuid("identity.invitation"));
}
