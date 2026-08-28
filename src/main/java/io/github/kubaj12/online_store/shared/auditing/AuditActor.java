package io.github.kubaj12.online_store.shared.auditing;

import java.util.Objects;
import java.util.UUID;

/** The authenticated user responsible for an audited change. */
public record AuditActor(UUID userId) {

	public AuditActor {
		Objects.requireNonNull(userId, "userId must not be null");
	}

}
