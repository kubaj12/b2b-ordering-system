# Identity Persistence Contract

`V003__introduce_identity_access.sql` is the immutable persistence contract for Phase 2 identity
data. The JDBC `InitialAdminBootstrapService` is the executable insert-only seed layered on top of
that schema. Read-only authentication services load credentials and current access snapshots from
`identity_user`; the browser request boundary compares current ACTIVE status and `security_version`
on each protected request. Login completion now writes `last_login_at` and `updated_at` atomically
with the reset of its persistent throttle pair; the persistent `identity_session` registry remains
a later account-wide invalidation contract.

The seed normalizes its configured email, checks for an existing account, hashes a valid password
at runtime, and inserts with `ON CONFLICT ON CONSTRAINT identity_user_email_uq DO NOTHING`.
Collisions preserve every existing account column, including blocked status, role, security version,
timestamps, last login, identifier, and password hash. It has no account update or delete path and
does not fabricate an authenticated audit actor.

## Tables

`identity_user` stores application-supplied UUID account identifiers, canonical unique email
addresses, BCrypt password hashes, role, status, security version, last login time, and explicit
UTC creation/update timestamps. Roles are `CUSTOMER`, `EMPLOYEE`, and `ADMIN`. Statuses are
`ACTIVE` and `BLOCKED`.

`identity_session` stores server-side session registry rows. `session_id_hash` is a unique
32-byte SHA-256 digest of the servlet session id. The stored `security_version` is the account
version captured at login. Runtime session validation must join the active account, require a
matching version, require no revocation, and require `expires_at > :now`.

`identity_invitation` stores invitation lifecycle state. `token_hash` is a unique 32-byte SHA-256
digest of an unpredictable invitation token. Invitations use normalized email, allow only
`CUSTOMER` or `EMPLOYEE` roles, and support `PENDING`, `ACCEPTED`, `EXPIRED`, and `REVOKED`
statuses. Only one pending invitation may exist for an email across roles through
`identity_invitation_pending_email_uq`. Issuance supplies a seven-day expiry using the injectable
clock. Validity requires `expires_at > :now`; equality means expired.

`identity_password_reset_token` stores reset-token lifecycle state. `token_hash` is a unique
32-byte SHA-256 digest of an unpredictable reset token. Reset issuance supplies a one-hour expiry
using the injectable clock. Validity requires `expires_at > :now`; equality means expired.

`identity_login_throttle` stores persistent login-throttle state keyed by
`(identity_hash, source_address)`. `identity_hash` is the 32-byte SHA-256 digest of the normalized
attempted identity and intentionally has no account foreign key so nonexistent-account attempts can
be counted. `source_address` must be a host address, stored as IPv4 `/32` or IPv6 `/128`.
Rows with zero failed attempts must not carry `last_failed_at`; rows with one or more failed
attempts must carry it.

## Normalization And Time

Emails are stored only in canonical form: `email = lower(btrim(email))`, nonempty, and without
whitespace. The database rejects noncanonical input instead of rewriting it. Full email syntax
validation belongs at the later application boundary.

All identity timestamps are `TIMESTAMPTZ(6)` and must be supplied by the application. The migration
does not define database-clock defaults. Non-UTC offsets round-trip to the same instant with
microsecond precision under the repository's UTC database-session configuration. Lifecycle
timestamps such as session `last_seen_at`/`revoked_at`, invitation `accepted_at`/`revoked_at`,
reset-token `consumed_at`/`revoked_at`, and throttle `window_started_at`/`last_failed_at` must not
be later than the row's `updated_at`.

## Indexes

The schema defines cleanup indexes on session, invitation, reset-token, and throttle expiry columns:
`identity_session_expires_at_idx`, `identity_invitation_expires_at_idx`,
`identity_password_reset_token_expires_at_idx`, and `identity_login_throttle_expires_at_idx`.

Account-wide invalidation and token lookups are supported by `identity_session_user_id_idx`,
`identity_invitation_invited_by_user_id_idx`, `identity_invitation_accepted_user_id_idx`, and
`identity_password_reset_token_user_id_idx`.

## Future Transaction Contracts

Later block, password-reset, and password-change commands must rotate `security_version` and
connect registry revocation to the servlet-session check. Invitation/reset token workflows remain
pending slices.

The schema supports later service implementations that must use transactions and conditional
updates for one-way lifecycle changes. Invitation acceptance should update only a pending,
unexpired row and create the account in the same transaction. Invitation resend should serialize by
normalized email, revoke or expire the older pending row, then insert the replacement. Expiration
does not automatically change invitation status.

Reset consumption should update only an unconsumed, unrevoked, unexpired token. Password replacement
must happen in the same transaction, rotate the account security version, and revoke other
outstanding reset tokens.

Blocking or password replacement must atomically rotate `identity_user.security_version` and
invalidate sessions. Stored session snapshots are intentionally not foreign keys to the current
account version, so stale sessions remain representable and can be excluded by validation queries.

Login throttling derives the servlet peer address, ignores forwarded headers, hashes
`NormalizedEmail.normalizeAttempt` with SHA-256, and atomically locks or inserts the
`(identity_hash, source_address)` key. The default is five failures in a fixed 15-minute window,
with a 15-minute block and 24-hour retention. Equality at block, window, and expiry boundaries is
available again. State transitions use microsecond UTC instants and clamp backward clock movement
to the persisted update time. Successful completion resets only its own pair. JDBC cleanup is
bounded to 100 expired rows and uses `SKIP LOCKED`. The guarded account update matches UUID,
canonical email, role, ACTIVE status, and security version.

## Audit Actor Foreign Key

`V003` adds `audit_event_acting_user_id_fk` from `audit_event.acting_user_id` to
`identity_user(id)` with `ON DELETE RESTRICT`. It is added as `NOT VALID` first so V002 schemas that
already contain historical audit actor UUIDs without account rows can still upgrade without losing
history or fabricating credentials.

Use this query to find historical actors that prevent validation:

```sql
SELECT DISTINCT audit.acting_user_id
FROM audit_event audit
LEFT JOIN identity_user actor ON actor.id = audit.acting_user_id
WHERE actor.id IS NULL;
```

After legitimate reconciliation, validate the constraint explicitly:

```sql
ALTER TABLE audit_event VALIDATE CONSTRAINT audit_event_acting_user_id_fk;
```

Historical actors cannot be reconstructed from UUIDs alone. Preserve the audit rows unless there is
a separate, legitimate source for the missing account identity.
