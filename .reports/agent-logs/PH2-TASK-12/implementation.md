# PH2-TASK-12 implementation report

## Outcome

Completed the Phase 2 security verification task. The repository's unit, PostgreSQL integration, MVC/template, architecture, migration, and application-context tests pass together. The plan item is marked complete.

The test inventory was audited against every behavior named by the task. Existing focused tests already supplied the required behavioral assertions; this task validated the matrix as a whole and fixed the test runner so Mockito-based unit and MVC tests run reliably on current JDKs.

## Change made

- Configured Maven Surefire to start `mockito-core` as a Java agent. Mockito 5's inline mock maker otherwise attempted dynamic self-attachment and failed in the managed environment on Java 25/26. The configured agent uses Spring Boot's managed `${mockito.version}` and Maven's local repository path, so all Maven test profiles inherit the same deterministic setup.
- Marked the Phase 2 verification checklist item complete in `plan.md`.

## Verification coverage

- Bootstrap restart and collisions: `BootstrapAdminStartupIntegrationTests`, `InitialAdminBootstrapIntegrationTests`, `InitialAdminBootstrapServiceTests`, and `JdbcInitialAdminAccountStoreTests` cover restarts, preservation of existing rows, role/status collisions, concurrent startup, and competing committed/rolled-back inserts.
- Normalization and uniqueness races: `NormalizedEmailTests`, `IdentityAccessPersistenceTests`, and `InvitationIntegrationTests` cover canonical email handling, database uniqueness, cross-role pending invitation uniqueness, competing issuance, acceptance, and resend transactions.
- Token expiry, replay, resend, and role tampering: `InvitationServiceTests`, `InvitationIntegrationTests`, `InvitationAcceptanceIntegrationTests`, `InvitationAcceptanceTests`, and `InvitationResendTests` cover boundary expiry, expiry during hashing/locking, one-time use, revocation, resend serialization, and deriving the account role from persisted invitation data.
- Password rules and hashing: `PasswordPolicyTests`, bootstrap service/integration tests, invitation tests, password-change tests, password-reset tests, and browser authentication tests cover the 12-code-point policy, malformed Unicode/byte limits, BCrypt storage and matching, and password replacement.
- SMTP failure recovery: `SmtpMailDeliveryTests`, `AccountMailIntegrationTests`, `PasswordResetReviewRegressionTests`, and `RecordingMailDeliveryTests` cover adapter error classification, commit-before-send behavior, failed invitation resend, failed reset request recovery, queue saturation, and generic responses.
- Login throttling: `LoginThrottlePolicyTests`, `LoginAttemptKeyTests`, `LoginAttemptServiceTests`, `LoginAttemptIntegrationTests`, `JdbcLoginThrottleStoreTests`, and `BrowserFormLoginTests` cover normalized identity/source keys, fixed-window boundaries, isolation, committed failures, rollback, threshold races, and enumeration-resistant behavior.
- Anonymous boundaries, role checks, and CSRF: `BrowserSecurityConfigurationTests` and controller MVC tests cover the public route allowlist, authentication of all other routes, administrator/employee/customer matrices, ordinary form CSRF, anonymous command CSRF, and HTMX header CSRF for POST/PUT/PATCH/DELETE.
- Blocked login and recovery: `AccountAuthenticationProviderTests`, `BrowserFormLoginTests`, `BrowserAuthenticationIntegrationTests`, `AccountMailIntegrationTests`, and `PasswordResetReviewRegressionTests` cover generic blocked-account rejection, equivalent password work for missing/blocked accounts, generic reset requests, and the rule that reset never unblocks an account.
- Login/block/reset races and stale sessions: `AccountStatusIntegrationTests`, `LoginAttemptIntegrationTests`, `PasswordResetIntegrationTests`, `PasswordChangeIntegrationTests`, `EmployeeAccountIntegrationTests`, and `BrowserAuthenticationIntegrationTests` cover serialized block/login outcomes, guarded successful-login completion, concurrent token consumption, security-version rotation, session revocation, stale login completion rejection, and stale-session rejection after unblock.

## Commands and results

All successful runs used Java 25.0.1, matching `pom.xml`.

- `./mvnw -Punit-tests test` — 111 tests, 0 failures, 0 errors, 0 skipped.
- `./mvnw -Pmvc-template-tests test` — 91 tests, 0 failures, 0 errors, 0 skipped.
- `./mvnw -Ppostgresql-integration-tests test` — 101 tests, 0 failures, 0 errors, 0 skipped; PostgreSQL 18.4 via Testcontainers.
- `./mvnw test` — 328 tests, 0 failures, 0 errors, 0 skipped. This also verifies architecture and Flyway migration checks alongside the requested categories.

An initial sandboxed PostgreSQL run could not access `/var/run/docker.sock`; it was rerun with approved Docker access and passed. Before the Surefire agent change, Mockito-based tests failed during test infrastructure initialization because dynamic agent attachment was unavailable. No application assertion failed.
