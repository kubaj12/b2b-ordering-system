package io.github.kubaj12.online_store.shared.auditing;

/** Records audit events atomically with the application operation that caused them. */
public interface AuditEventRecorder {

	/**
	 * Records an event in the caller's existing transaction.
	 *
	 * @throws org.springframework.transaction.IllegalTransactionStateException if no transaction exists
	 */
	void record(AuditEvent event);

}
