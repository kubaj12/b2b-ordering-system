# PH3-TASK-05 implementation

## Outcome

Customer invitation acceptance now completes the customer-specific work inside the existing identity invitation transaction. A successful acceptance:

1. revalidates the pending NIP while holding the shared transaction-scoped NIP advisory lock;
2. creates the active `CUSTOMER` identity account;
3. copies every company, billing-address, and phone field from `customer_invitation_data` into the one-to-one `customer_profile`;
4. deletes the consumed pending payload;
5. marks the identity invitation `ACCEPTED`.

Any exception in the customer lifecycle handler propagates through `InvitationService`'s `TransactionTemplate`, rolling back the earlier account insert together with profile/payload/invitation mutations.

## Changes

- Updated `CustomerInvitationLifecycleHandler.onAccept` to revalidate NIP availability before profile creation and translate persistence uniqueness conflicts to the public invitation-unavailable result.
- Updated `JdbcCustomerAdministrationStore.requireInvitationNipAvailable` to:
  - acquire the same NIP advisory lock used by customer invitation creation and profile editing;
  - reject an existing customer profile with that NIP;
  - reject another live pending invitation with that NIP;
  - exclude the invitation currently being accepted from its own conflict check.
- Added unit coverage proving NIP validation happens before profile creation and prevents creation on conflict.
- Added PostgreSQL integration coverage proving:
  - the account is normalized, assigned role `CUSTOMER`, and activated;
  - the complete pending payload is copied exactly;
  - the payload is removed and invitation consumed;
  - a late NIP conflict rolls back the new account/profile and leaves the invitation and payload pending.
- Marked the Phase 3 task complete in `plan.md`.

## Verification

- Focused suite: `./mvnw test -Dtest=CustomerInvitationWiringTests,CustomerInvitationLifecycleIntegrationTests,InvitationServiceTests`
  - 17 tests passed.
- Full suite: `./mvnw test`
  - 354 tests passed, 0 failures, 0 errors, 0 skipped.

The full suite required Docker access for PostgreSQL Testcontainers. An initial sandboxed full-suite attempt could not access the Docker socket; the approved rerun completed successfully.

## Scope note

Pre-existing working-tree changes in browser security/configuration tests and the earlier portions of `plan.md` were preserved and not modified as part of this task.
