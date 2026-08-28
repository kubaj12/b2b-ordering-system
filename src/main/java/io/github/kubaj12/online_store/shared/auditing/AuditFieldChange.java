package io.github.kubaj12.online_store.shared.auditing;

/** A validated field change created by an event's typed {@link AuditField} definition. */
public final class AuditFieldChange<T> {

	private final AuditField<T> field;
	private final String from;
	private final String to;

	AuditFieldChange(AuditField<T> field, String from, String to) {
		this.field = field;
		this.from = from;
		this.to = to;
	}

	AuditField<T> field() {
		return field;
	}

	String from() {
		return from;
	}

	String to() {
		return to;
	}

}
