/**
 * Actor, target, change metadata, and audit-event recording shared by module application services.
 *
 * <p>Events are written synchronously in the transaction that performs the audited change. Domain
 * modules own their event names and define exact metadata allow-lists using typed, non-text fields.
 * Each event also binds its target to a UUID or positive numeric internal-ID definition. Raw
 * passwords, credentials, and invitation or reset tokens cannot cross the public API.</p>
 */
package io.github.kubaj12.online_store.shared.auditing;
