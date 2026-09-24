# PH3-TASK-01 implementation report

## Outcome

Added the Phase 3 customer persistence migration. Flyway now creates a staff-only, one-to-one customer profile for activated customer accounts and a separate retained company/billing payload for customer invitations.

## Schema changes

- Added `V004__introduce_customer_profiles.sql`.
- Added `customer_profile`, keyed by `user_id`, with a composite foreign key that permits only an `identity_user` whose role is `CUSTOMER`. The primary key enforces at most one profile per account.
- Added `customer_invitation_data`, keyed by `invitation_id`, with a composite foreign key that permits only an `identity_invitation` whose role is `CUSTOMER`. The row holds the payload while that invitation owns it; acceptance copies it to the profile, while resend transfers it to the replacement invitation.
- Added supporting `(id, role)` unique constraints to `identity_user` and `identity_invitation` for the role-aware foreign keys.
- Added mandatory company name and complete billing address columns: street, building number, postal code, city, and country.
- Added nullable unit-number and phone columns. When supplied, both must contain non-whitespace text.
- Fixed billing country to the ISO code `PL` and constrained postal codes to canonical Polish `NN-NNN` form.
- Constrained NIP to its normalized database representation of exactly ten ASCII digits. Accepted customer profiles have a unique NIP through `customer_profile_nip_uq`.
- Added unique NIP constraints to both profiles and invitation payloads. Resend must transfer retained company/billing data by deleting the revoked invitation's payload before inserting the replacement payload in the same transaction. The later service task must also revalidate conflicts between the profile and invitation tables atomically.
- Added application-supplied `TIMESTAMPTZ(6)` creation/update fields and chronological update constraints to both tables.
- Added comments documenting data visibility, canonical NIP storage, Poland-only country representation, and invitation retention semantics.

Checksum validation and user-input normalization are intentionally left for the next planned task, which introduces reusable NIP, postal-code, phone, and address validation. This migration establishes the canonical storage boundary and rejects noncanonical values.

## Tests

Added `CustomerPersistenceTests` with PostgreSQL coverage for:

- one-to-one profile and invitation-payload cardinality;
- customer-only account and invitation relationships;
- unique accepted-profile NIPs and canonical ten-digit NIP storage;
- unique invitation NIPs and payload transfer to a replacement invitation;
- mandatory billing data, Poland-only country/postal constraints, optional phone/unit behavior, and timestamp chronology;
- named database constraints and SQL states for rejected rows.

## Verification

- `./mvnw test -Dtest=DatabaseMigrationTests,CustomerPersistenceTests` — 9 tests passed, 0 failures, 0 errors, 0 skipped.
- `./mvnw test` — 332 tests passed, 0 failures, 0 errors, 0 skipped. This includes empty-schema and sequential Flyway migration checks through `V004`, all PostgreSQL integration tests, MVC tests, unit tests, architecture checks, and application-context tests.

Existing unrelated working-tree changes in `plan.md` and Phase 2 security files were preserved and not modified by this task.
