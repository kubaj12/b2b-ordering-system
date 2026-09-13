# Testing conventions

The test suite separates pure unit tests, PostgreSQL repository tests, application-service
integration tests, migration tests, and Spring MVC tests. Tests that exercise persistence use the
pinned PostgreSQL Testcontainer; H2 or another in-memory SQL substitute is not part of the test
strategy.

Running the complete suite requires a working Docker environment:

```shell
./mvnw test
```

`./mvnw test` remains the complete local suite and intentionally runs every category together.

## Verification commands

Compile production and test sources without requiring Docker:

```shell
./mvnw clean test-compile
```

Run focused categories when a smaller feedback loop is useful:

```shell
./mvnw -Punit-tests test
./mvnw -Ppostgresql-integration-tests test
./mvnw -Pmvc-template-tests test
./mvnw -Parchitecture-checks test
./mvnw -Pmigration-checks test
```

## Test categories

| Category | Tag | Maven profile | Docker | Intended tests |
| --- | --- | --- | --- | --- |
| Unit | untagged | `unit-tests` | No | Pure value objects, deterministic adapters, configuration validation, time primitives, browser response primitives, and other tests that do not need Spring MVC, architecture scanning, or PostgreSQL. |
| PostgreSQL integration | `postgresql` excluding `migration` | `postgresql-integration-tests` | Yes | Repository slices and committed application-service integration tests using the pinned PostgreSQL Testcontainer. |
| MVC and templates | `mvc` | `mvc-template-tests` | No | Browser MVC, security, HTMX, Thymeleaf rendering, and web-error slices. |
| Architecture | `architecture` | `architecture-checks` | No | ArchUnit module-boundary rules and ordinary architecture tests. |
| Migration | `migration` | `migration-checks` | Yes | Empty-database startup and Flyway migration-forward scenarios. |

The focused PostgreSQL integration profile excludes migration-tagged tests so migration checks run
only in the dedicated migration category.

## Future test classification

Leave pure unit tests untagged. Untagged tests are the unit-test category and must not require
Docker.

Annotate JPA or JDBC adapter tests with `@PostgreSqlRepositoryTest`. It provides a focused data
slice, runs the real Flyway migrations, connects through the Testcontainers service connection,
activates the `test` profile, and rolls each test transaction back automatically.

Extend `PostgreSqlServiceTestSupport` when a test must exercise real service transaction
boundaries. These tests inherit the PostgreSQL category through `@PostgreSqlServiceTest`.

Use `@BrowserMvcTest(controllers = ...)` for feature MVC, security, and template slices. Classes
that intentionally use raw `@WebMvcTest` must be tagged directly with `@Tag("mvc")`.

Use ArchUnit's `@ArchTag("architecture")` for `@AnalyzeClasses` suites because those classes are
discovered by ArchUnit's JUnit engine. Use JUnit's `@Tag("architecture")` for ordinary
Jupiter-based architecture tests.

Tag migration scenarios with `@Tag("migration")`. Keep using `MigrationFixture` for migration
setup and never use `Flyway.clean()`.

## Repository tests

Use repository tests for mappings, queries, locking clauses, database constraints, indexes, and
PostgreSQL-specific behavior. Flush before asserting a database constraint that is deferred until
SQL execution. Do not disable rollback merely to share records between test methods.

`IdentityAccessPersistenceTests` covers the Phase 2 identity schema contract from
`docs/identity-persistence.md`: account normalization, token hashes, lifecycle checks, expiry
boundaries, session-version snapshots, login-throttle keys, indexes, and uniqueness races.

## Application-service integration tests

Application-service integration tests do not run inside a test-managed transaction, so commits,
rollbacks, after-commit behavior, and concurrent transactions remain observable.

Before every test, the base fixture:

- verifies that the datasource points to the managed PostgreSQL Testcontainer;
- truncates application tables and restarts their identities while retaining
  `flyway_schema_history`;
- resets the deterministic UTC clock;
- clears the recording mail adapter and in-memory image storage.

The target verification is mandatory because service-test cleanup is destructive. Do not call the
cleaner directly and do not use this base class with a manually configured or shared datasource.
Tests using this shared committed-state database must not run concurrently with one another;
concurrency behavior should be created and joined inside one test.

The fixture exposes `testClock()`, `mailDelivery()`, and `imageStorage()` to subclasses. Set the
clock explicitly when the exact instant is part of an assertion; otherwise it starts at
`2026-01-15T10:15:30Z` for every test.

## Flyway migration tests

`MigrationFixture` creates a new schema inside the disposable PostgreSQL container for each
scenario. It configures Flyway with the production migration location and the same validation,
baseline, ordering, and clean-disabled rules as the application.

`DatabaseMigrationTests` verifies migration from an empty schema, validation, a no-op second
migration, and version-by-version forward application. When a later migration transforms or
backfills data, add a focused test that:

1. creates a fresh `MigrationSchema`;
2. calls `flywayTo(previousVersion).migrate()`;
3. inserts representative old-version rows through the schema's `JdbcTemplate`;
4. calls `flyway().migrate()`;
5. asserts the transformed data and constraints.

Never use `Flyway.clean()` for migration setup. Fresh schemas give each scenario an empty starting
point while keeping clean disabled.

`DatabaseMigrationTests` also covers the V002-to-V003 audit actor foreign-key upgrade. Empty V002
schemas validate the new constraint immediately, while populated V002 schemas with unmatched
historical actors retain the rows, keep the foreign key unvalidated, and still reject new unmatched
audit events.

## Deterministic outbound adapters

Application-service tests replace external effects with module-owned test adapters:

- `RecordingMailDelivery` records ordered attempts and successful deliveries and can make the next
  attempt fail as either temporary or permanent;
- `InMemoryImageStorage` returns sequential opaque references, defensively copies binary content,
  supports seeding and deletion, and can fail its next store, load, or delete operation.

Neither adapter opens a network connection or writes to the filesystem. Assert captured values
directly; do not print rendered mail bodies because they may contain activation or reset tokens.
The mail value object's diagnostic text redacts both bodies.

Production SMTP and durable image-storage adapter contract tests will be added with those adapters
in their feature phases. The deterministic adapters are for application behavior, failure paths,
and retry/lifecycle tests.

## Spring MVC and security tests

`@BrowserMvcTest` imports the real browser security filter chain and the shared HTMX
argument-resolver configuration. Mock or import only the application boundary required by the
selected controller.

`SecurityTestUsers` supplies stable CUSTOMER, EMPLOYEE, and ADMIN request post-processors for role
matrices. Controller tests must cover both allowed and denied roles, verify that denial happens
before an application-service call, and derive object ownership from the authenticated principal
rather than request parameters.

For every state-changing browser operation, cover ordinary form CSRF and HTMX header CSRF. Keep
anonymous invitation and password-reset commands under CSRF protection as well. HTMX affects only
the HTML representation and redirect transport; it must not select a different service,
authorization rule, or validation path.

## Continuous verification

GitHub Actions runs `Continuous Verification` for pull requests targeting `main`, pushes to `main`,
and manual `workflow_dispatch` runs. The workflow uses pinned `ubuntu-24.04` runners, Temurin 25,
and Maven caching based on `pom.xml`.

The workflow exposes six checks: `Compilation`, `Unit tests`, `PostgreSQL integration tests`,
`MVC and template tests`, `Architecture checks`, and `Empty-database migrations`. The test matrix
uses `fail-fast: false`, so one failing category does not cancel the remaining categories.

CI requires Docker for the PostgreSQL integration, migration, and complete-suite paths because the
project provisions `postgres:18.4-bookworm` through the existing Testcontainers
`@ServiceConnection`. CI does not need a GitHub Actions PostgreSQL service or database
credentials.

Each focused test category uploads `target/surefire-reports` as a short-retention artifact even
when the category fails. This keeps category-specific diagnostics available for PostgreSQL and
migration failures.
