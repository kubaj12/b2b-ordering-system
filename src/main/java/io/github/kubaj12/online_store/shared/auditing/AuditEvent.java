package io.github.kubaj12.online_store.shared.auditing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A semantic audit event created through its event-specific {@link AuditEventType} definition. */
public final class AuditEvent {

	private final AuditEventType<?> type;
	private final String targetType;
	private final String targetId;
	private final AuditActor actor;
	private final Map<String, Map<String, String>> changeMetadata;

	AuditEvent(
			AuditEventType<?> type,
			String targetType,
			String targetId,
			AuditActor actor,
			Map<String, Map<String, String>> changeMetadata
	) {
		this.type = Objects.requireNonNull(type, "type must not be null");
		this.targetType = Objects.requireNonNull(targetType, "targetType must not be null");
		this.targetId = Objects.requireNonNull(targetId, "targetId must not be null");
		this.actor = Objects.requireNonNull(actor, "actor must not be null");
		this.changeMetadata = immutableCopy(changeMetadata);
	}

	public AuditEventType<?> type() {
		return type;
	}

	String targetType() {
		return targetType;
	}

	String targetId() {
		return targetId;
	}

	public AuditActor actor() {
		return actor;
	}

	Map<String, Map<String, String>> changeMetadata() {
		return changeMetadata;
	}

	private static Map<String, Map<String, String>> immutableCopy(
			Map<String, Map<String, String>> metadata
	) {
		Objects.requireNonNull(metadata, "changeMetadata must not be null");
		var copy = new LinkedHashMap<String, Map<String, String>>(metadata.size());
		metadata.forEach((field, change) -> copy.put(
				field,
				Collections.unmodifiableMap(new LinkedHashMap<>(change))
		));
		return Collections.unmodifiableMap(copy);
	}

}
