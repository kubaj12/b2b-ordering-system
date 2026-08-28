# Staged Change Report: Shared Audit-Event Foundation

## Review scope

This report describes the staged changes relative to `HEAD` commit `278b410` (`feat: persist timestamps as UTC, add Europe/Warsaw display zone and injectable Clock`). It is a change-impact review, not a defect review.

- Diff reviewed: `git diff --cached HEAD`
- Staged footprint: 15 files, 1,075 insertions, 3 deletions
- Primary change: a shared, transaction-bound audit-event API and PostgreSQL persistence model
- Supporting changes: schema migration, architectural enforcement, unit/integration coverage, migration-version expectation, and completion of the corresponding Phase 1 plan item
- Excluded from scope: the unstaged `.gitignore` modification

## Executive summary

The staged work establishes the common infrastructure that later feature modules will use to record security and business changes. It adds a small public API for defining typed audit events, a package-private JDBC implementation that stores them synchronously in the caller's transaction, and Flyway migration `V002` for an append-oriented `audit_event` table.

The design treats event definitions as module-owned contracts. Each event has a stable namespaced type, a typed internal target identifier, an authenticated actor, and an exact allowlist of non-text change fields. The API deliberately excludes arbitrary text values and arbitrary text target IDs, reducing the chance that passwords, credentials, session values, invitation tokens, or reset tokens enter audit metadata.

No feature module begins emitting events in this diff. The change provides the recording foundation; identity, catalog, inventory, and order event producers remain work for later phases. There is also no audit query/read service or user-facing audit view in this change.

## Files changed

| Area | Files | Change |
| --- | --- | --- |
| Public audit model/API | `AuditActor`, `AuditEvent`, `AuditEventType`, `AuditField`, `AuditFieldChange`, `AuditTargetType`, `AuditEventRecorder` | Adds actor, event, target, field-change, validation, and recorder abstractions. |
| Persistence adapter | `JdbcAuditEventRecorder` | Adds synchronous JSONB persistence through `JdbcTemplate`. |
| Database | `V002__introduce_audit_events.sql` | Creates the audit table, constraints, comments, and lookup indexes. |
| Package contract | `shared/auditing/package-info.java` | Documents ownership, transactional semantics, and secret-exclusion rules. |
| Integration tests | `AuditEventPersistenceTests` | Covers persistence, timestamps, transactions, rollback, size limits, database constraints, and indexes. |
| Value/API tests | `AuditValueTypesTests` | Covers naming, typing, ranges, allowlists, no-op rejection, sensitive names, and restricted factories. |
| Architecture tests | `ModuleArchitectureTests` | Restricts audit recording dependencies to feature-module application layers. |
| Application migration test | `OnlineStoreApplicationTests` | Advances the expected current Flyway version from `001` to `002`. |
| Delivery plan | `plan.md` | Marks the common audit-event table/service milestone complete. |

## Architectural decisions

### 1. Auditing is a shared-kernel capability

The audit abstractions live under `shared.auditing`, allowing all six feature modules to reuse one recording contract while keeping event definitions in the module that owns the business action. The package documentation states that module application services are the intended consumers.

This creates a clear split:

- Shared auditing owns safe representation and persistence mechanics.
- Feature modules own semantic event names, target definitions, allowed fields, and the decision to record an event.
- The new ArchUnit rule prevents feature web, domain, or persistence code from depending directly on `AuditEventRecorder`; only application-layer code may cross that boundary.

### 2. Event schemas are explicit, typed objects

`AuditEventType<T>` binds together:

- a stable namespaced event name, such as `catalog.sku-pricing.changed`;
- one `AuditTargetType<T>`;
- an exact set of allowed `AuditField<?>` definitions.

An event can only be created through its type definition. Field admission is based on the exact `AuditField` instance, not merely a matching field name. Duplicate changes, undeclared fields, excess fields, and missing metadata for an event that declares fields are rejected before persistence.

The resulting `AuditEvent` takes an immutable defensive copy of its nested metadata map. Its persistence-oriented target and metadata accessors remain package-private, while public consumers can access the semantic event type and actor.

### 3. The public API excludes free-form sensitive values by construction

Audit fields can only be created through four factories:

- enum values;
- bounded, fixed-scale decimals;
- bounded integers;
- booleans.

There is no string/text field factory and no public `AuditField` constructor. Field names are also screened for sensitive terms including password, credential, secret, cookie, session, hash, code, and token.

Targets follow the same restrictive approach: they can only be non-nil UUIDs or positive `long` identifiers. There is no arbitrary text target factory, so public tokens and credentials cannot be used as target IDs through this API.

### 4. Audit writes participate in the owning business transaction

`AuditEventRecorder` is a public port; `JdbcAuditEventRecorder` is a package-private Spring service implementing that port. Its `record` method uses `Propagation.MANDATORY`, which means callers must already be inside a transaction.

The audit insert therefore commits and rolls back with the operation being audited. Recording is synchronous: serialization, validation, or database failure is part of the business transaction's outcome rather than a deferred best-effort side effect.

### 5. Time comes from the injected clock and is persisted as UTC

The JDBC recorder uses the existing injected `Clock`, converts its instant to an `OffsetDateTime` at `ZoneOffset.UTC`, and writes it to `TIMESTAMPTZ(6)`. This follows the project's established UTC-persistence policy and makes audit timestamps deterministic in tests.

### 6. PostgreSQL stores a compact relational envelope with JSONB changes

Migration `V002` creates one `audit_event` table with relational columns for the most important lookup dimensions and JSONB for event-specific change metadata:

- identity primary key;
- event type;
- target type and canonical target ID;
- acting user UUID;
- UTC-capable occurrence timestamp;
- structured change metadata.

Database checks mirror key API invariants: event and target naming formats, canonical UUID/positive-number target IDs, JSON object metadata, and a 16 KiB metadata ceiling. Indexes support reverse-chronological lookup by target, actor, and event type, with `id DESC` as a deterministic tie-breaker.

The actor column is currently a logical UUID reference. The migration explicitly defers its foreign key until the identity/user table is introduced.

### 7. The schema is append-oriented

The table comment defines the records as append-only security and business change events. This diff adds insert behavior only: it introduces no update/delete API and no audit-record entity with mutable persistence semantics.

## Changes in system behavior

### Startup and schema behavior

- Flyway now advances an empty database to version `002`.
- Startup creates `audit_event` and its three lookup indexes.
- The application context now registers `JdbcAuditEventRecorder` as the implementation of `AuditEventRecorder`.

### Event construction behavior

- Callers create an `AuditActor` from a non-null user UUID.
- Callers define targets as UUID or positive numeric internal IDs.
- Callers define stable event names and their exact field allowlists.
- Field changes may represent creation (`null` to value) or removal (value to `null`).
- A no-op change, where old and new values are equal, is rejected.
- Decimal values must fit their declared bounds and scale; integers must fit their declared range; enum and boolean values are encoded canonically.
- Events with declared fields require change metadata; events with no declared fields can persist an explicit empty JSON object.
- Constructed metadata cannot be mutated through the event after creation.

### Recording behavior

- `record(event)` requires an existing Spring transaction.
- The event is inserted immediately using JDBC and JSONB serialization.
- The occurrence time is taken from the injected clock and normalized to UTC.
- Metadata larger than 16,384 UTF-8 bytes is rejected before insertion; the database also enforces a 16,384-byte JSON representation limit.
- Rolling back the owning transaction also removes the audit insert.

### Architectural behavior

- Feature-module application layers may depend on `AuditEventRecorder`.
- Feature-module web, domain, and persistence layers are rejected by the architecture test if they access the recorder directly.
- Audit code also remains covered by the existing rule that production code must use the injected clock rather than ambient system time.

### Behavior intentionally not added yet

- No domain-specific event producer is included.
- No audit query repository, read service, controller, UI, retention job, or export is introduced.
- `acting_user_id` does not yet have a physical foreign key because the user table is planned for a later migration.

## High-risk areas

These are change-sensitive areas to account for during rollout and future development; they are not defect findings.

### Transactional coupling

Audit persistence is deliberately part of the business transaction. This provides atomicity, but it also makes the audit database insert, JSON serialization, constraints, and table availability part of the success path for every future audited operation. Event producers should treat a rejected audit payload as a rejection of the owning operation and keep event construction deterministic.

### Database migration and operational growth

`V002` adds a production table and three write-maintained indexes. Because the model is append-oriented and this diff defines no retention or partitioning policy, storage volume and index growth will track the eventual audit-event rate. Backup, restore, retention, and access-control policies should be established before event volume becomes significant.

### Event-contract governance

Event names, target names, field names, and encoded enum values become durable data contracts once producers ship. Renaming them later would split historical queries unless compatibility or migration is planned. Module owners should centralize definitions and treat them as versioned schema, even though most of that schema is expressed in Java rather than database DDL.

### Sensitive-data boundary

The type system substantially narrows what can be recorded, and field-name screening adds another guard. Future contributors still decide which enums, numeric values, and booleans are appropriate to persist. Reviews of new event definitions remain security-relevant because audit data generally has broad retention and operational visibility.

### Deferred actor referential integrity

Actor UUIDs can be stored before the identity table and its foreign key exist. The migration documents this sequencing decision. The later identity migration will need to preserve existing audit data while adding the intended relationship and account-lifecycle semantics.

### Size limits across representations

The 16 KiB ceiling is enforced both on serialized UTF-8 JSON in Java and on PostgreSQL's JSONB text representation. Future changes to serialization, field-count limits, or metadata structure should keep both layers coordinated.

### Timestamp and lookup semantics

Audit ordering uses `occurred_at DESC, id DESC`, with timestamps supplied by the application clock. Operational tooling and future read APIs should preserve that ordering convention, especially when deterministic or adjusted clocks are used in tests and maintenance workflows.

## Key modified-code snippets

### Typed event definition and exact allowlist enforcement

```java
var metadata = new LinkedHashMap<String, Map<String, String>>(changes.length);
for (AuditFieldChange<?> change : changes) {
    Objects.requireNonNull(change, "audit field change must not be null");
    AuditField<?> field = change.field();
    if (allowedFields.get(field.name()) != field) {
        throw new IllegalArgumentException(
                "audit field is not allowed for event type: " + field.name()
        );
    }
    if (metadata.putIfAbsent(field.name(), encoded(change)) != null) {
        throw new IllegalArgumentException("duplicate audit field change: " + field.name());
    }
}
```

This makes each module-owned event definition the runtime schema for its metadata and prevents an unrelated field object with the same name from substituting for the declared field.

### Restricted field factories and validated changes

```java
public static <E extends Enum<E>> AuditField<E> enumeration(String name, Class<E> enumType)
public static AuditField<BigDecimal> decimal(
        String name, int scale, BigDecimal minimum, BigDecimal maximum)
public static AuditField<Integer> integer(String name, int minimum, int maximum)
public static AuditField<Boolean> flag(String name)

public AuditFieldChange<T> change(T from, T to) {
    if (Objects.equals(from, to)) {
        throw new IllegalArgumentException("audit change must alter the value");
    }
    return new AuditFieldChange<>(this, encode(from), encode(to));
}
```

These are the only public construction paths for change metadata; arbitrary strings are intentionally absent.

### Mandatory transactional recording

```java
@Override
@Transactional(propagation = Propagation.MANDATORY)
public void record(AuditEvent event) {
    Objects.requireNonNull(event, "event must not be null");
    String metadata = serialize(event);

    jdbcTemplate.update("""
            INSERT INTO audit_event (
                event_type,
                target_type,
                target_id,
                acting_user_id,
                occurred_at,
                change_metadata
            ) VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
            """,
            event.type().value(),
            event.targetType(),
            event.targetId(),
            event.actor().userId(),
            OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
            metadata
    );
}
```

The propagation setting and direct insert are the core of the atomic audit-write decision.

### Database envelope and constraints

```sql
CREATE TABLE audit_event (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    target_type VARCHAR(80) NOT NULL,
    target_id VARCHAR(160) NOT NULL,
    acting_user_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ(6) NOT NULL,
    change_metadata JSONB NOT NULL,
    CONSTRAINT audit_event_change_metadata_object
        CHECK (JSONB_TYPEOF(change_metadata) = 'object'),
    CONSTRAINT audit_event_change_metadata_size
        CHECK (OCTET_LENGTH(change_metadata::TEXT) <= 16384)
);
```

The complete migration additionally constrains event/target formats and adds indexes for target-, actor-, and event-type-based history.

### Application-layer ownership rule

```java
if (layerOf(javaClass).filter("application"::equals).isPresent()) {
    return;
}

for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
    if (dependency.getTargetClass().isEquivalentTo(AuditEventRecorder.class)) {
        events.add(violation(
                dependency,
                "Audit boundary bypass: " + dependency.getDescription()
        ));
    }
}
```

This codifies the decision that audit recording belongs to application-service orchestration rather than controllers, domain objects, or persistence adapters in feature modules.

## Verification introduced by the diff

The staged tests establish the intended contracts at three levels:

- `AuditValueTypesTests` exercises event/target naming, target restrictions, field factories, sensitive-name rejection, exact field allowlists, duplicate/no-op handling, and numeric validation.
- `AuditEventPersistenceTests` exercises real PostgreSQL persistence with Testcontainers, the injected UTC instant, empty metadata, required transactions, rollback atomicity, encoded-size rejection, database constraints, and index creation.
- `ModuleArchitectureTests` enforces application-layer access to the recorder.
- `OnlineStoreApplicationTests` confirms that an empty database reaches Flyway version `002` with no pending migrations.

The test code also demonstrates the intended producer style: define reusable target, field, and event constants, build a typed event from validated changes and an actor, then record it inside the owning transaction.

## Overall impact

The staged diff completes the Phase 1 audit infrastructure milestone and introduces a strong foundation for later identity, pricing, inventory, and order-status audit producers. Its main architectural characteristic is prevention by construction: events are module-defined, field sets are explicit, values are typed and bounded, targets are internal IDs, persistence is atomic, and package/layer boundaries are executable rules rather than documentation alone.
