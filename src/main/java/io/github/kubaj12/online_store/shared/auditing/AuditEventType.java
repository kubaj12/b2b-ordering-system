package io.github.kubaj12.online_store.shared.auditing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** A stable, module-owned audit event name bound to its exact typed metadata allowlist. */
public final class AuditEventType<T> {

	static final int MAX_LENGTH = 100;
	static final int MAX_FIELDS = 32;

	private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9_-]*)+$");

	private final String value;
	private final AuditTargetType<T> targetType;
	private final Map<String, AuditField<?>> allowedFields;

	public AuditEventType(
			String value,
			AuditTargetType<T> targetType,
			AuditField<?>... allowedFields
	) {
		Objects.requireNonNull(value, "value must not be null");
		Objects.requireNonNull(targetType, "targetType must not be null");
		Objects.requireNonNull(allowedFields, "allowedFields must not be null");
		if (value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
			throw new IllegalArgumentException("invalid audit event type: " + value);
		}
		if (allowedFields.length > MAX_FIELDS) {
			throw new IllegalArgumentException("audit event type must not allow more than " + MAX_FIELDS + " fields");
		}

		var fieldsByName = new LinkedHashMap<String, AuditField<?>>(allowedFields.length);
		for (AuditField<?> field : allowedFields) {
			Objects.requireNonNull(field, "allowed audit field must not be null");
			if (fieldsByName.putIfAbsent(field.name(), field) != null) {
				throw new IllegalArgumentException("duplicate allowed audit field: " + field.name());
			}
		}

		this.value = value;
		this.targetType = targetType;
		this.allowedFields = Collections.unmodifiableMap(fieldsByName);
	}

	public String value() {
		return value;
	}

	public AuditEvent event(
			T targetId,
			AuditActor actor,
			AuditFieldChange<?>... changes
	) {
		Objects.requireNonNull(actor, "actor must not be null");
		Objects.requireNonNull(changes, "changes must not be null");
		if (!allowedFields.isEmpty() && changes.length == 0) {
			throw new IllegalArgumentException("audit event requires change metadata");
		}
		if (changes.length > allowedFields.size()) {
			throw new IllegalArgumentException("audit event contains too many metadata fields");
		}

		var metadata = new LinkedHashMap<String, Map<String, String>>(changes.length);
		for (AuditFieldChange<?> change : changes) {
			Objects.requireNonNull(change, "audit field change must not be null");
			AuditField<?> field = change.field();
			if (allowedFields.get(field.name()) != field) {
				throw new IllegalArgumentException("audit field is not allowed for event type: " + field.name());
			}
			if (metadata.putIfAbsent(field.name(), encoded(change)) != null) {
				throw new IllegalArgumentException("duplicate audit field change: " + field.name());
			}
		}

		return new AuditEvent(
				this,
				targetType.value(),
				targetType.encode(targetId),
				actor,
				metadata
		);
	}

	private static Map<String, String> encoded(AuditFieldChange<?> change) {
		var encoded = new LinkedHashMap<String, String>(2);
		encoded.put("from", change.from());
		encoded.put("to", change.to());
		return encoded;
	}

}
