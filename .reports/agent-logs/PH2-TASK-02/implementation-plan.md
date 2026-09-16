# Implementation Plan

## Approach

Implement bootstrap as a composition-root `ApplicationRunner` invoking an identity/access application service. Keep credential configuration in the existing root properties class, password/email rules in JDK-only identity domain classes, and account SQL behind an application-owned port implemented with `JdbcTemplate`. This follows `ModuleArchitectureTests` and reuses the JDBC, explicit UTC timestamps, injected clock, and transaction patterns already present in `JdbcAuditEventRecorder` and the PostgreSQL fixtures.

`V003__introduce_identity_access.sql` already supplies everything required: UUID account IDs, canonical unique email, role/status constraints, security version, and timestamps. Creation must use `ON CONFLICT ON CONSTRAINT identity_user_email_uq DO NOTHING`; a preliminary existence query is only an optimization. Existing accounts always produce a no-op, including blocked administrators and customer/employee email collisions. Do not add account update SQL, migrations, a global administrator-count rule, or schema defaults. Bootstrap is keyed by configured normalized email; the repository does not require a singleton ADMIN across all emails.

The current implementation only binds bootstrap settings and validates a duplicated 12-character rule. There are no account mappings, account services, or password encoders. `BrowserSecurityConfiguration` still uses default form login and Boot's generated in-memory user; connecting login to persisted accounts belongs to the following roadmap task. Add reusable BCrypt infrastructure now without implementing that authentication slice.

Use one password policy for configured initial passwords and application password creation. Reject passwords above BCrypt's 72-byte UTF-8 input limit before hashing; the locally resolved Spring Security 7.1.0 implementation explicitly rejects those inputs. Prevent disclosure at diagnostic sources rather than introducing a regex that cannot reliably recognize arbitrary passwords or token strings. Preserve the existing redacted configuration/mail diagnostics, typed audit metadata, and sanitized web exception logging.

## Changes

### 1. Introduce reusable email and password rules and the BCrypt bean

**Files:**

* `src/main/java/io/github/kubaj12/online_store/identityaccess/domain/NormalizedEmail.java` — new.
* `src/main/java/io/github/kubaj12/online_store/identityaccess/domain/PasswordPolicy.java` — new.
* `src/main/java/io/github/kubaj12/online_store/ApplicationConfiguration.java`.
* `src/test/java/io/github/kubaj12/online_store/identityaccess/domain/NormalizedEmailTests.java` — new.
* `src/test/java/io/github/kubaj12/online_store/identityaccess/domain/PasswordPolicyTests.java` — new.
* `src/test/java/io/github/kubaj12/online_store/ApplicationConfigurationTests.java`.

**Changes:**

1. Add an immutable `NormalizedEmail` value object with `public static NormalizedEmail of(String input)` and `value()`. Normalize surrounding whitespace with `strip()` and lowercase with `Locale.ROOT`, then require a nonempty address of at most 254 characters, no embedded whitespace, and the existing bootstrap syntax pattern `^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$`. Use Unicode-aware whitespace matching so application validation does not accept invisible whitespace that should be rejected. Preserve the full-address lowercase convention required by the existing schema; do not introduce provider-specific alias/dot transformations. Implement value equality and hash code. Diagnostic text must show `<configured>` rather than the address; `value()` is the explicit persistence accessor. All validation failures have fixed messages without submitted input.
2. Add a stateless, JDK-only `PasswordPolicy` with public constants `MINIMUM_CHARACTERS = 12` and `MAXIMUM_UTF8_BYTES = 72`, pure validity methods usable by configuration constraints, and `validate(String password)` for application enforcement. Require nonnull, nonblank input, at least 12 Unicode code points, and at most 72 bytes using `StandardCharsets.UTF_8`. Count characters with `codePointCount(0, password.length())`, so supplementary characters cannot satisfy the minimum merely through surrogate-pair length. Reject unpaired surrogate code units with a fixed invalid-text message so distinct malformed inputs cannot collapse to UTF-8 replacement bytes. Do not trim, case-fold, normalize, require particular character categories, or otherwise transform passwords. Leading/trailing spaces in an otherwise valid password remain significant.
3. Keep error messages separate and constant: missing/blank password, fewer than 12 characters, invalid Unicode text, and more than 72 UTF-8 bytes. Do not interpolate passwords, hashes, or rejected values and do not wrap exceptions containing them. Expose pure checks for the minimum/blank rule and the encoding/byte-limit rule so `@AssertTrue` constraints can give accurate safe messages.
4. Register one `PasswordEncoder` bean in `ApplicationConfiguration`, returning `new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, 12)`. Use BCrypt's built-in cryptographic salt generation. Persist the ordinary 60-character `$2b$12$...` value without a `{bcrypt}` prefix. Existing fixture hashes use `$2a$10$...`; BCrypt recognizes those without migration. Keep minimum-length validation at password-creation boundaries rather than inside `matches`, which future login will use against existing hashes. No new Maven dependency or independently pinned Spring Security version is needed; `spring-boot-starter-security` already supplies crypto.

**Tests:**

* Email tests: mixed case and surrounding spaces produce the same canonical value/equality; embedded whitespace, missing parts, empty/null input, and addresses exceeding 254 characters are rejected; diagnostics and exception messages never contain submitted addresses. Include a locale-independence assertion with restoration of the previous default locale.
* Policy tests: null, empty, blank, and 11-character inputs fail; exactly 12 characters pass; 11 supplementary code points fail and 12 pass; unpaired surrogates fail; valid Polish characters work; exactly 72 UTF-8 bytes pass and 73 fail, including multibyte strings whose Java length is below 72. Verify valid surrounding spaces are counted and remain untouched.
* Extend `ApplicationConfigurationTests` to assert exactly one `PasswordEncoder`, `$2b$12$` output, successful matching, mismatch rejection, and distinct salted hashes for the same synthetic password. Assert hash diagnostics are never printed. Keep these tests untagged and Docker-free.

### 2. Consolidate bootstrap validation and make early failures safe

**Files:**

* `src/main/java/io/github/kubaj12/online_store/BootstrapAdminProperties.java`.
* `src/main/java/io/github/kubaj12/online_store/ApplicationConfiguration.java`.
* `src/main/java/io/github/kubaj12/online_store/ProductionConfiguration.java`.
* `src/test/java/io/github/kubaj12/online_store/ApplicationConfigurationTests.java`.
* `src/test/java/io/github/kubaj12/online_store/ProductionStartupValidationTests.java`.
* `src/test/java/io/github/kubaj12/online_store/BootstrapStartupValidationTests.java` — new.

**Changes:**

1. Retain the package-private properties class, constructor binding, `enabled()`, `email()`, `password()`, and redacted `toString()`. The accessors may retain the configured strings; normalize email only through `NormalizedEmail` when validating and seeding. Replace its private password constant and email regex with the shared domain checks. Update `isEmailValidWhenEnabled()` to validate normalized email. Update `isPasswordValidWhenEnabled()` to share the minimum/nonblank rule and add a separate `@AssertTrue` check for valid Unicode/72-byte input. Every constraint is conditional on `enabled` and uses a fixed message.
2. Do not replace these boolean constraints with field-level `@Size`, `@Pattern`, or a password-bearing validation object: Boot's `BindValidationFailureAnalyzer` prints field rejected values. Existing tests only inspect causal messages, so add complete-output coverage below.
3. Add a static `@Bean` returning `BeanFactoryPostProcessor` in `ApplicationConfiguration`, following `ProductionConfiguration.productionConfigurationValidator(Environment)`. Read `app.bootstrap.admin.enabled` as a string and explicitly accept only `true`/`false` case-insensitively; missing/empty values mean disabled. An invalid flag throws a fixed property-name-only `IllegalStateException`, preventing the ordinary boolean converter from reporting its raw rejected value. When disabled, return without reading email/password or validating their contents.
4. When enabled, the postprocessor validates email through `NormalizedEmail.of` and password through `PasswordPolicy.validate`, translating failures to property-name-and-rule-only `IllegalStateException` messages without the original cause. This runs before singleton infrastructure initialization in every profile, prevents invalid credentials from reaching Flyway/database initialization or the runner, and avoids raw values in startup binding failures. Do not resolve normal configuration beans or inject the application service into this static postprocessor.
5. Remove the bootstrap-validation block and private password constant from `ProductionConfiguration`; keep the existing SMTP email pattern and every other production invariant. The common validator applies equally in production, while the existing production postprocessor retains its database/mail/cookie validation responsibilities.
6. Enabled configuration remains required and valid on every restart, even when the email already exists. Operators must disable bootstrap to remove credentials. Disabled bootstrap accepts absent/invalid leftover email/password without account work.

**Tests:**

* Update `ApplicationConfigurationTests` to cover normalized mixed-case/padded email, missing email/password, malformed/too-long email, blank password, 11/12-character boundary, Unicode character counts, and 72/73-byte boundary in both ordinary and production configuration runners. Preserve all unrelated production configuration tests.
* Test invalid boolean configuration containing a distinctive synthetic secret; neither its exception chain nor captured logs may contain that value. Check safe defaults still bind to disabled/empty settings.
* Add `BootstrapStartupValidationTests` using real `SpringApplication` with `WebApplicationType.NONE`, disabled Compose, and a datasource URL pointing to port 1, following `ProductionStartupValidationTests`. Use `OutputCaptureExtension`/`CapturedOutput` to inspect complete output for invalid bootstrap email/password/flag cases. Assert the fixed bootstrap failure occurs before infrastructure errors; output and causal messages must exclude the synthetic email/password, JDBC URL, and `Connection refused`. Supply otherwise valid production settings when testing the production profile.
* Verify disabled bootstrap skips credential validation with deliberately invalid leftover credentials. Close any successfully started contexts; restore temporary logger levels after assertions.

### 3. Add the insert-only account persistence port and adapter

**Files:**

* `src/main/java/io/github/kubaj12/online_store/identityaccess/application/InitialAdminAccountStore.java` — new.
* `src/main/java/io/github/kubaj12/online_store/identityaccess/persistence/JdbcInitialAdminAccountStore.java` — new.
* `src/test/java/io/github/kubaj12/online_store/identityaccess/persistence/JdbcInitialAdminAccountStoreTests.java` — new.

**Changes:**

1. Define an application-owned port with `boolean existsByEmail(NormalizedEmail email)` and `boolean insertIfAbsent(UUID id, NormalizedEmail email, String passwordHash, Instant now)`. Do not expose role/status setters, account upserts, raw-password parameters, or password-bearing result objects. The lookup returns existence only and never selects `password_hash`.
2. Implement the port in a constructor-injected `@Repository` using `JdbcTemplate`. Reuse the SQL text-block and positional binding pattern from `JdbcAuditEventRecorder`. Use a bound email in `SELECT EXISTS (SELECT 1 FROM identity_user WHERE email = ?)`.
3. Make `insertIfAbsent` transactional with `Propagation.MANDATORY`, like the audit recorder, and execute precisely this insert:

   ```sql
   INSERT INTO identity_user (
       id, email, password_hash, role, status, security_version,
       last_login_at, created_at, updated_at
   ) VALUES (?, ?, ?, 'ADMIN', 'ACTIVE', 0, NULL, ?, ?)
   ON CONFLICT ON CONSTRAINT identity_user_email_uq DO NOTHING
   ```

   Bind `email.value()`, the runtime hash, and the same UTC instant twice through `OffsetDateTime.ofInstant(now, ZoneOffset.UTC)`, reusing `IdentityDatabaseFixture`'s conversion pattern. Return true for update count 1 and false for 0; unexpected counts must fail with a fixed message. No `DO UPDATE`, merge, delete, or broad `ON CONFLICT DO NOTHING`: naming the email constraint leaves unrelated constraint/UUID failures visible.
4. PostgreSQL's unique constraint coordinates independent processes and waits for a conflicting uncommitted insert. The losing seed observes update count 0 after the winner commits; if the other transaction rolls back, the seed can create the account. Do not add JVM locks, retry loops for uniqueness violations, or a seed marker table.

**Tests:**

* Use `@PostgreSqlRepositoryTest` and explicitly import the JDBC adapter, following the repository test conventions. Test lookup, successful insert, canonical email persistence, UTC creation/update equality, `ADMIN`/`ACTIVE`, version 0, and null last login.
* Insert an existing row with `IdentityDatabaseFixture`, call the adapter with another ID/hash/time, and assert update count false and all columns unchanged. Parameterize across all three roles and both statuses, including a nonzero security version and populated last-login timestamp.
* Verify a duplicate UUID with a different email still fails rather than being swallowed; use the test-managed transaction/savepoint conventions where a SQL error would otherwise invalidate subsequent assertions. Verify invoking the mandatory insert through its Spring proxy inside a `TransactionTemplate` callback with `PROPAGATION_NOT_SUPPORTED` fails before creating a row; this suspends the repository slice's surrounding test transaction without disabling rollback.
* Keep committed concurrency tests in the service-test fixture in step 4; repository slices roll back and are unsuitable for testing independent startup commits.

### 4. Implement idempotent bootstrap with observable transaction boundaries

**Files:**

* `src/main/java/io/github/kubaj12/online_store/identityaccess/application/InitialAdminBootstrapService.java` — new.
* `src/test/java/io/github/kubaj12/online_store/identityaccess/application/InitialAdminBootstrapServiceTests.java` — new.
* `src/test/java/io/github/kubaj12/online_store/identityaccess/application/InitialAdminBootstrapIntegrationTests.java` — new.

**Changes:**

1. Add a constructor-injected `@Service` with `InitialAdminAccountStore`, `PasswordEncoder`, `Clock`, and the existing Spring `TransactionTemplate` bean. Expose `bootstrap(String email, String password)` returning a nested credential-free enum `CREATED` or `ALREADY_EXISTS`. Do not accept a role/status from callers or depend on root `BootstrapAdminProperties`; feature-to-composition-root dependencies are forbidden.
2. Validate and normalize arguments with the domain rules before any query or hash operation. Validation errors remain fixed and secret-free. Then check existence; if present return `ALREADY_EXISTS` immediately without hashing or writing. This optimization avoids repeated BCrypt work; it is not the concurrency guarantee.
3. If absent, hash the original password with the injected BCrypt encoder outside the transaction, obtain `UUID.randomUUID()`, and read one instant from the injected clock. Truncate the instant to microseconds to align exactly with `TIMESTAMPTZ(6)` and deterministic assertions. Execute `store.insertIfAbsent(...)` through `TransactionTemplate.execute`; map its boolean result to the enum only after the transaction commits. This avoids self-invocation/proxy mistakes and keeps BCrypt computation outside the database transaction.
4. If another account is created between lookup and insert, return `ALREADY_EXISTS` on the conditional insert's false result. Do not inspect its role, verify its password, reactivate it, rotate its security version, update timestamps, or promote it. Preserve every stored field, including last login and ID.
5. Wrap runtime failures from lookup, encoding, insert, and transaction completion at this security boundary. Log a generated diagnostic UUID and exception class name only, following `LocalizedWebExceptionHandler`'s source-sanitization approach; do not pass the throwable, its message, causes, suppressed exceptions, SQL details, or credential-bearing objects to the logger. Throw a new fixed `IllegalStateException` with the diagnostic reference and no original cause or suppressed exceptions. This ensures `SpringApplication` cannot print a PostgreSQL failing-row detail containing the hash. Fail startup for operational failures; do not convert them to `ALREADY_EXISTS` or retry indefinitely. Validation remains outside this wrapper so safe policy errors stay specific.
6. Do not emit an audit event for the seed. `AuditActor` explicitly represents an authenticated user and the table requires a real acting account; there is no system actor contract. Bootstrap has no authenticated actor. Preserve the existing audit API and use credential-free operational outcomes instead of fabricating an actor.

**Tests:**

* Untagged unit tests: invalid input produces no store/encoder calls; an existing email skips hashing and insertion; creation passes canonical email and a BCrypt hash rather than plaintext; a false insert after a false lookup produces `ALREADY_EXISTS`. Capture logger events with the existing Logback `ListAppender` pattern and verify fixed outcomes, no raw input/hash, and no throwable proxy.
* Inject lookup/encoder/insert/transaction-completion failures with distinctive synthetic secrets in messages, causes, and suppressed exceptions. Verify the escaping exception contains only the safe reference, has no cause/suppressed exceptions, and logged arguments/messages contain no secrets. Include a transaction manager that fails on commit to prove the wrapper covers completion outside the transaction callback.
* Extend `PostgreSqlServiceTestSupport` for integration tests, using its guarded cleanup, deterministic `TestClock`, real transactions, and `IdentityDatabaseFixture`. Keep automatic startup bootstrap disabled in this suite and call the service explicitly after the base `@BeforeEach` cleanup.
* Verify first creation's complete row, runtime hash matching, and a no-op second invocation after changing both configured password and clock. Read all row columns before and after and assert equality; do not print the row/hash as diagnostics. Check old password still matches and the replacement seed password does not.
* Parameterize existing-account collisions over `CUSTOMER`, `EMPLOYEE`, and `ADMIN`, each `ACTIVE` and `BLOCKED`. Seed nonzero security version, historical timestamps, and last login; assert complete preservation and exactly one account. Also test an unrelated existing account does not prevent creation for another email, and mixed-case/padded input collides canonically.
* Use bounded `CountDownLatch`/executor coordination, following `IdentityAccessPersistenceTests`, for two independent service calls with the same normalized email and different valid passwords. Construct service instances with the real store/transaction infrastructure and an encoder wrapper that gates both calls after their absent lookups, then delegates to the real BCrypt bean. Release both, join with timeouts, and assert exactly one `CREATED`, one `ALREADY_EXISTS`, one row, and a hash matching only the successful creator's password. This exercises competing insert transactions rather than merely consecutive calls.
* Add deterministic collision races against an ordinary JDBC transaction creating a customer/employee: hold that insertion uncommitted while bootstrap reaches insertion, then commit; assert the seed returns `ALREADY_EXISTS` and preserves the ordinary account. Repeat with the competing transaction rolling back; bootstrap must then return `CREATED`. Always release latches and close/join executors in `finally`; do not use sleeps or parallelize fixture cleanup across test classes.

### 5. Connect bootstrap to application startup without coupling MVC slices

**Files:**

* `src/main/java/io/github/kubaj12/online_store/BootstrapAdminConfiguration.java` — new.
* `src/test/java/io/github/kubaj12/online_store/BootstrapAdminConfigurationTests.java` — new.
* `src/test/java/io/github/kubaj12/online_store/BootstrapAdminStartupIntegrationTests.java` — new.
* `src/test/java/io/github/kubaj12/online_store/OnlineStoreApplicationTests.java`.

**Changes:**

1. Add a root `@Configuration(proxyBeanMethods = false)` with an `ApplicationRunner` bean conditional on `app.bootstrap.admin.enabled=true` using `@ConditionalOnProperty`. Inject the properties and `InitialAdminBootstrapService` into the bean method rather than the configuration constructor, so disabled bootstrap does not demand the service in focused configuration contexts.
2. In the runner, call `service.bootstrap(properties.email(), properties.password())` once. Log a fixed creation message for `CREATED`; log a fixed skip message for `ALREADY_EXISTS` saying an account already occupies the configured email and no account changes were made. Log neither the address nor any credential. A collision is a safe no-op and successful startup, even if that account is not an administrator; do not silently choose an alternative email.
3. Run in both servlet and non-web applications and all profiles when explicitly enabled. `ApplicationRunner` executes after normal context initialization, so Flyway and datasource setup precede account insertion; propagate the service's sanitized operational failure to fail application startup. Do not execute from a properties constructor, `@PostConstruct`, a SQL initializer, or a migration.
4. Keep `application-test.properties`'s explicit disabled setting. Service-fixture cleanup runs after runners, so startup tests must inspect state without inheriting that cleanup for their assertions. Existing MVC slices should not import this runner/configuration or need account-service mocks. Do not modify `BrowserSecurityConfiguration`, its anonymous matchers, CSRF behavior, or test-user helpers.

**Tests:**

* `BootstrapAdminConfigurationTests`: use `ApplicationContextRunner` with explicitly imported root configurations and a mocked service. Assert runner absence when disabled, presence when enabled, and exactly one call with configured values when manually invoking the runner. Context runners do not automatically execute `ApplicationRunner`. Assert created/skip logs contain fixed messages without credentials and sanitized failures propagate.
* `BootstrapAdminStartupIntegrationTests`: tag `postgresql`; use the pinned container and `MigrationFixture` to allocate an isolated schema, then start and close a real non-web `SpringApplication` twice against that same schema with bootstrap enabled. Supply direct datasource properties and disable Compose; supply an explicit deterministic clock via test configuration. Assert migrations precede the seed, one active ADMIN exists after first startup, and the entire row is unchanged after restart with another valid password. Use direct properties to override the `test` profile's disabled flag. Do not run the service cleaner between those contexts.
* In a second isolated schema, migrate and preinsert a blocked customer/employee, start the real application with the colliding email, and verify startup succeeds and the row stays unchanged. No fallback administrator should appear.
* Extend the existing disabled-startup test in `OnlineStoreApplicationTests` to assert no bootstrap runner bean exists. Avoid a global account-count assertion against its shared database; other suites legitimately populate that database.

### 6. Pin safe diagnostic defaults and extend redaction regression coverage

**Files:**

* `src/main/resources/application.properties`.
* `src/test/java/io/github/kubaj12/online_store/ApplicationConfigurationTests.java`.
* `src/test/java/io/github/kubaj12/online_store/BootstrapAdminStartupIntegrationTests.java`.
* `src/test/java/io/github/kubaj12/online_store/shared/web/error/SharedWebTestController.java`.
* `src/test/java/io/github/kubaj12/online_store/shared/web/error/LocalizedWebExceptionHandlerTests.java`.
* `src/test/java/io/github/kubaj12/online_store/testsupport/RecordingMailDeliveryTests.java`.

**Changes:**

1. Keep the existing environment mappings and disabled bootstrap default. Add explicit safe logging defaults with explanatory comments: `logging.level.org.springframework.jdbc.core=INFO` to prevent bind-value TRACE output; `logging.level.org.hibernate.orm.jdbc.bind=INFO`; `logging.level.com.zaxxer.hikari=INFO`; `logging.level.org.springframework.web=INFO`; `logging.level.org.springframework.security=INFO`; and `logging.level.org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration=ERROR` to suppress Boot's existing generated-password warning while database authentication remains a later task. Add `spring.mail.properties[mail.debug]=false`. These specific categories must remain safe even when a deployment enables root DEBUG for ordinary diagnostics. Document that enabling credential-bearing TRACE/debug categories is prohibited; arbitrary external logging overrides cannot be made safe by redacted `toString()`.
2. Preserve all existing `spring.web.error.include-*` settings. Reuse `LocalizedWebExceptionHandler` unchanged: it already logs bounded exception class names and stack frames without messages or request URLs. Preserve `OutgoingMail.toString()`'s redacted bodies and the audit metadata API's exclusion of arbitrary strings/hashes/tokens. Do not introduce a general logging interceptor, expose configuration through an endpoint, or expand audit metadata types.
3. Add a test-only failure route with a raw synthetic password, BCrypt hash, invitation/reset token, SMTP credential, and token-bearing email body in its exception message, nested cause, and suppressed exception. Call it with a token-looking path/query and sensitive request headers to establish that web error logging does not include request content. Keep the route under `/test/errors/...` and avoid echoing submitted values into HTML.

**Tests:**

* Extend property-binding tests to assert the explicit safe logger levels and mail-debug default. Add an isolated real **servlet** startup case with `server.port=0` and root DEBUG while preserving category overrides; this must actually initialize Boot's temporary in-memory authentication configuration, unlike the non-web restart cases. Captured output must exclude the seeded password/hash and the `Using generated security password:` warning. Close the server/context in `finally`.
* Extend `LocalizedWebExceptionHandlerTests` using its existing `ListAppender` setup for both full-page and HTMX representations. Assert safe exception types/stack frames/reference remain available, HTML stays localized with the existing statuses/HTMX markers, no sensitive fixtures appear in HTML/logs, and every captured event has no throwable proxy. Restore/detach the appender in `finally`.
* Extend `RecordingMailDeliveryTests.redactsBodiesFromDiagnosticText()` with token-bearing plain-text/HTML bodies and assert neither token nor raw link appears in diagnostics. Keep synthetic values in tests only; never print captured mail bodies.
* Retain existing audit value-type tests as regression coverage; no production audit/mail/web source change is required unless these added tests uncover a specific disclosure.

### 7. Document the executable bootstrap contract and testing boundaries

**Files:**

* `.env.example`.
* `docs/configuration.md`.
* `docs/identity-persistence.md`.
* `docs/testing.md`.

**Changes:**

1. Replace `.env.example`'s obsolete “seed uses these settings in the future” comment with the implemented opt-in contract. Keep `B2B_BOOTSTRAP_ADMIN_ENABLED=false` and both credential values empty; do not add example passwords or a real administrator address.
2. Update the environment-variable table and bootstrap section in `docs/configuration.md`: explain email normalization, nonblank 12-code-point minimum, valid Unicode and 72-byte UTF-8 maximum, unchanged password whitespace, runtime BCrypt hashing, and creation as `ADMIN`/`ACTIVE`/version 0. Describe startup ordering, conditional insert, restart/concurrent-startup behavior, and collision as a skip that cannot promote/reactivate/reset any existing account. Clarify that changing bootstrap password does not rotate a stored password, and changing configured email is a separate opt-in seed target rather than an account rename.
3. Explain enabling bootstrap using exported variables or deployment secret configuration, checking the credential-free created/skipped outcome, then disabling bootstrap and removing initial credentials. Enabled restarts still require valid credentials. State that this task creates a persisted administrator but database-backed form login is implemented by the next roadmap slice; do not provide a misleading working-login smoke test.
4. Document source-safe logging rules: never log raw passwords, password hashes, token-bearing paths/links, servlet headers/session IDs, SMTP credentials, or mail bodies; use fixed outcomes and diagnostic references. Explain the explicit framework logger defaults and why bind/SMTP debug logging must stay disabled. Do not include secrets in sample commands, URL parameters, screenshots, or failure reports.
5. Update `docs/identity-persistence.md`'s opening to distinguish the immutable V003 schema from the new JDBC seed service; document the named email-conflict insert and complete preservation of collisions. Keep the existing invitation/reset/session future contracts intact. Explain that seed does not fabricate an authenticated audit actor.
6. Add the new unit/repository/service/startup test classes and their categories to `docs/testing.md`, including committed concurrency tests, isolated schemas for true restarts, and the rule that inherited service cleanup must not erase the seed before startup assertions. Reuse the existing test profiles and CI matrix; do not change `pom.xml` or the workflow. Leave roadmap completion checkboxes unchanged until implementation and verification actually pass.

**Tests:**

* Review documented property names against `application.properties` and `.env.example`; check every command works with the existing Maven wrapper/profiles.
* Confirm no production credential or token has been added to documentation/configuration, and none of V001–V003 has changed.

## Verification

Run with the repository's pinned Java 25 toolchain. The existing GitHub Actions matrix already covers these categories; retain the pinned `postgres:18.4-bookworm` container and PostgreSQL-only persistence verification.

```shell
./mvnw clean test-compile
./mvnw -Punit-tests test
./mvnw -Ppostgresql-integration-tests test
./mvnw -Pmvc-template-tests test
./mvnw -Parchitecture-checks test
./mvnw -Pmigration-checks test
./mvnw test
```

Compilation, units, MVC, and architecture checks do not require Docker; PostgreSQL, migrations, and the complete suite do. Verify new classes are discovered in their intended categories and that the complete suite includes them. In particular, `COMPOSITION_ROOT_DEPENDENCIES_ARE_ONE_WAY`, `MODULE_LAYERS_FOLLOW_PORTS_AND_ADAPTERS`, `DOMAIN_CODE_USES_ONLY_DOMAIN_SAFE_DEPENDENCIES`, and injected-clock rules must pass without weakening architecture tests.

Assess the complete behavior through the isolated real-startup tests: migration then first creation, restart with a changed valid secret preserving every column, disabled startup doing no seed work, collision preserving a blocked nonadministrator, and two independent transactions yielding one creator. Confirm unrelated database failures still fail startup with sanitized references; complete console capture, exception causes/suppressed exceptions, and Logback event throwable proxies must be secret-free.

For an optional local smoke test, use a disposable database and credentials supplied through the environment, enable bootstrap, start twice, and compare account metadata and the stored hash through a private database session without printing credentials to reports. Disable bootstrap and unset credentials afterward. Do not use a shared deployment for destructive test cleanup, and do not attempt database-backed login until the next authentication task is implemented.

Review `git diff --check`, the final diff, and Surefire diagnostics for unintended schema, route, configuration-default, or secret disclosures. No applied migration should change, and no account UPDATE/DELETE path should be introduced by this seed.

## Open Questions

None.
