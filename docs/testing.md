# Testing conventions

The test suite separates pure unit tests, PostgreSQL repository tests, application-service
integration tests, migration tests, and Spring MVC tests. Tests that exercise persistence use the
pinned PostgreSQL Testcontainer; H2 or another in-memory SQL substitute is not part of the test
strategy.

Running the complete suite requires a working Docker environment:

```shell
./mvnw test
```

## Repository tests

Annotate JPA or JDBC adapter tests with `@PostgreSqlRepositoryTest`. It provides a focused data
slice, runs the real Flyway migrations, connects through the Testcontainers service connection,
activates the `test` profile, and rolls each test transaction back automatically.

Use repository tests for mappings, queries, locking clauses, database constraints, indexes, and
PostgreSQL-specific behavior. Flush before asserting a database constraint that is deferred until
SQL execution. Do not disable rollback merely to share records between test methods.

## Application-service integration tests

Extend `PostgreSqlServiceTestSupport` when a test must exercise real service transaction
boundaries. These tests do not run inside a test-managed transaction, so commits, rollbacks,
after-commit behavior, and concurrent transactions remain observable.

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

Use `@BrowserMvcTest(controllers = ...)` for feature MVC slices. It imports the real browser
security filter chain and the shared HTMX argument-resolver configuration. Mock or import only the
application boundary required by the selected controller.

`SecurityTestUsers` supplies stable CUSTOMER, EMPLOYEE, and ADMIN request post-processors for role
matrices. Controller tests must cover both allowed and denied roles, verify that denial happens
before an application-service call, and derive object ownership from the authenticated principal
rather than request parameters.

For every state-changing browser operation, cover ordinary form CSRF and HTMX header CSRF. Keep
anonymous invitation and password-reset commands under CSRF protection as well. HTMX affects only
the HTML representation and redirect transport; it must not select a different service,
authorization rule, or validation path.
