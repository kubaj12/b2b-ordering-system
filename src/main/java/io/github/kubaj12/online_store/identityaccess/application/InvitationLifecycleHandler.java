package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Instant;
import java.util.UUID;

/** Optional module-owned work that must share the invitation transaction. */
public interface InvitationLifecycleHandler {
    default void onResend(UUID previousInvitationId, UUID replacementInvitationId,
            String role, Instant now) { }
    default void onAccept(UUID invitationId, UUID accountId, String role, Instant now) { }
}
