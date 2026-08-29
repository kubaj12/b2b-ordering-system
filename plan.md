# B2B Ordering System — Development Plan

## Planning baseline

`project.md` is the authoritative scope. The repository currently contains a generated Spring Boot application, the selected core dependencies, PostgreSQL Docker Compose configuration, and a Testcontainers context-load test. It does not yet contain domain modules, Flyway migrations, security configuration, templates, or application features, so the roadmap begins with those foundations and does not repeat the existing project bootstrap.

The implementation must remain a private, server-rendered modular monolith. Payments, invoices/KSeF, ERP or warehouse integration, fulfillment and tracking, returns/complaints, CSV exchange, address books, public APIs, and other storefront features are explicitly outside this plan.

In the roadmap, **staff** means either an `EMPLOYEE` or an `ADMIN`; administrator-only employee-account actions are called out separately.

## Significant implementation assumptions

- The initial administrator is created by an idempotent bootstrap seed that reads the normalized email and initial password from environment/secret configuration, hashes the password at runtime, and never places credentials in a migration or version-controlled file.
- Uploaded SKU image binaries live in configurable durable file/object storage behind a catalog-owned storage interface; PostgreSQL stores only an opaque image reference and metadata. Image reads remain authenticated, while upload, replacement, and removal remain staff-only.
- Order email reliability uses a transactional outbox. Order acceptance records notification work atomically, then an after-commit dispatcher attempts delivery immediately and a scheduled worker retries temporary failures. SMTP latency or failure therefore cannot roll back an order.
- Session invalidation is enforced with a server-side session registry plus an account security version checked on authenticated requests. Blocking an account or resetting its password increments that version and invalidates every known session for the account.
- The numeric part of `ORD-YYYY-NNNNNN` is allocated from a concurrency-safe, per-calendar-year database counter and padded to six digits.

## Phase 1 — Modular foundation and delivery pipeline

**Depends on:** existing Spring Boot skeleton.

- [x] Establish top-level modules for identity/access, customers, catalog/pricing, inventory, cart/ordering, and notifications; give each module its own domain, application, persistence, and web boundaries and keep shared code limited to time, money, auditing, and web error primitives.
- [x] Add automated architecture checks that prevent controllers or persistence adapters from bypassing module application services and prevent cyclic module dependencies.
- [x] Configure Flyway as the only schema-management path, disable Hibernate schema mutation, pin PostgreSQL images used by local development/tests, and document forward-only migration and rollback/recovery conventions.
- [x] Configure all persisted timestamps as UTC and the application display zone as `Europe/Warsaw`; introduce an injectable clock for deterministic expiration, audit, and order-number tests.
- [x] Introduce a common audit-event table/service carrying event type, target, acting user, timestamp, and non-secret change metadata; domain phases below will add their required event producers.
- [x] Establish environment-based configuration for database, mail, image storage, base URL, cookie settings, and bootstrap credentials, with safe local/test defaults and no production secrets in the repository.
- [ ] Build the shared Polish Thymeleaf layout, Bootstrap/HTMX asset setup, navigation by role, form-error components, pagination components, accessible flash messages, and localized `404`, `403`, validation, conflict, and unexpected-error handling.
- [ ] Standardize full-page and HTMX request handling so both paths use the same application services, validation, authorization, CSRF checks, and error semantics; do not create a public API layer.
- [ ] Extend the existing PostgreSQL Testcontainers setup with migration tests, reusable repository/service fixtures, Spring MVC security tests, and deterministic mail/image test adapters.
- [ ] Add continuous verification for compilation, unit tests, PostgreSQL integration tests, MVC/template tests, architecture checks, and migration-from-empty-database checks.

**Completion outcome:** The application starts from an empty PostgreSQL database through Flyway, renders a Polish responsive shell and safe errors, enforces module boundaries, and has a repeatable automated verification pipeline ready for feature slices.

## Phase 2 — Authentication, account recovery, and employee administration

**Depends on:** Phase 1 migration, audit, web, configuration, and test foundations.

- [ ] Add migrations for users, server-side session/security-version data, invitations, password-reset tokens, and login-throttle state, including normalized unique email, role/status constraints, timestamps, token hashes, single-use markers, and expiry indexes.
- [ ] Implement the environment-backed initial `ADMIN` seed, BCrypt hashing, a 12-character minimum password policy, and secret-safe logging/redaction.
- [ ] Configure form login/logout and route rules: only login, invitation acceptance, password reset, static assets, and necessary error pages are anonymous; every other request requires an `ACTIVE` account.
- [ ] Implement successful-login timestamp updates and throttling by normalized identity plus request source without revealing whether an email exists.
- [ ] Implement random hashed invitation tokens with the `PENDING`, `ACCEPTED`, `EXPIRED`, and `REVOKED` lifecycle, seven-day validity, single use, and resend behavior that revokes the prior pending invitation before issuing a new token.
- [ ] Deliver the administrator employee-account slice: staff directory, `EMPLOYEE` invitation email, activation/password creation without a customer profile, and administrator-only block/unblock actions. Record invitation and account-status audit events with the actor.
- [ ] Implement forgotten-password email flow with random hashed one-hour tokens, single-use consumption, password replacement, security-version rotation, and invalidation of all existing sessions.
- [ ] Implement authenticated password change with current-password verification and the same session invalidation guarantees; expose no self-service role, email, company, or billing-data changes.
- [ ] Configure server-side session cookies as `Secure`, `HttpOnly`, and `SameSite=Lax`, and enable CSRF protection for ordinary and HTMX state-changing requests.
- [ ] Verify with unit/integration/MVC tests: normalization and uniqueness races, token expiry/replay/resend, password rules and hashing, throttling, anonymous-route boundaries, role checks, blocked-login rejection, live-session invalidation, and CSRF failures.

**Completion outcome:** A secret-seeded administrator can securely invite and administer employees; activated users can log in, change/reset passwords, and lose access immediately when blocked or reset, with all identity rules enforced server-side.

## Phase 3 — Customer invitation and internal customer administration

**Depends on:** Phase 2 invitation, authentication, mail, authorization, and audit services.

- [ ] Add migrations for one-to-one customer profiles and pending customer-invitation data, with mandatory company/billing fields, normalized unique NIP, Poland-only address constraints, optional phone/unit fields, and creation/update timestamps.
- [ ] Implement reusable Polish NIP normalization/checksum validation plus Polish postal-code, phone, and complete-address validation at form, service, and database boundaries where applicable.
- [ ] Build employee/admin customer list, create/invite, detail, and edit pages. Keep company and billing data strictly internal and prevent customer-role controllers/templates from loading it.
- [ ] Make customer invitation creation require a unique email and complete company/billing payload, retain that payload while pending, and send the activation link through the identity mail adapter.
- [ ] Accept a valid customer invitation in one transaction: revalidate uniqueness, create the `CUSTOMER` user, copy pending data to its one-to-one profile, activate the account, and consume the invitation. A failed transaction must leave no partial account/profile.
- [ ] Implement employee/admin resend and customer block/unblock actions; preserve profiles and historical references when accounts are blocked. Record customer invitation and account-status audit events.
- [ ] Enforce object-level rules so a customer cannot obtain any customer profile (including their own), another user's identifier, or administrative form by URL/request manipulation.
- [ ] Verify activation races, duplicate email/NIP handling, expired/revoked/accepted links, payload copying, Poland-only validation, profile edit persistence, block/session behavior, role matrix, and IDOR attempts with PostgreSQL and MVC tests.

**Completion outcome:** Staff can invite and maintain Polish business customers end to end, acceptance creates exactly one active customer/profile pair, and company/billing data remains staff-only.

## Phase 4 — Catalog, SKU images, pricing, and customer catalog browsing

**Depends on:** Phase 3 customer identities/profiles and Phase 1 image/test abstractions.

- [ ] Add migrations for products, SKUs, attribute definitions/values, SKU attribute assignments, price lists/items, customer price-list assignments, customer-specific prices, and image metadata. Include the specified catalog fields/timestamps and enforce unique SKU codes, one attribute value per definition per SKU, one assigned list per customer, one price per list/SKU or customer/SKU pair, non-negative stock, two-decimal net prices, VAT precision, and PLN currency invariants.
- [ ] Implement product, SKU, variant-attribute, active-state, and base-price/VAT application services with validation and history-safe behavior: records referenced by orders are inactivated rather than destructively removed.
- [ ] Build staff catalog pages to create/edit products and SKUs, assign variant values, set base net prices/VAT rates, and inspect net/VAT/gross reference amounts.
- [ ] Implement authenticated image serving and staff-only validated image upload, replacement, removal, storage cleanup, and thumbnail rendering without allowing user-controlled paths or executable content.
- [ ] Build staff price-list pages to name lists, maintain selected SKU prices, assign/unassign at most one list per customer, and maintain individual customer/SKU overrides.
- [ ] Implement one effective-price resolver with the required precedence (customer-specific, assigned list, base) and a shared decimal calculator that performs line-level `HALF_UP` VAT rounding and totals by summing rounded line amounts.
- [ ] Record all base-price, price-list price, customer-price, and VAT-rate changes through the audit service with old/new values and actor.
- [ ] Build the paginated customer catalog: product-name/SKU-code search, category filter, product descriptions and variants, authenticated thumbnails, exact current quantity, effective net price, VAT and gross display, and clear unavailable states. Hide inactive products/SKUs, retain active products/SKUs at zero stock, and disable adding unavailable variants.
- [ ] Enforce customer-scoped price resolution in application services so request parameters can never select another customer's price list or override.
- [ ] Verify schema constraints, attribute uniqueness, price precedence, currency/rounding edge cases, audit records, active/out-of-stock visibility, image authorization/lifecycle, pagination/search/filter behavior, and cross-customer price isolation.

**Completion outcome:** Staff can maintain the complete sellable catalog and negotiated pricing, while each customer can browse only active offerings with their own correctly rounded prices, VAT, thumbnails, and exact stock state.

## Phase 5 — Manual inventory management and concurrency

**Depends on:** Phase 4 SKU catalog and Phase 2 staff identity.

- [ ] Add an inventory-change migration and SKU concurrency/version support, storing SKU, previous/new quantity, acting employee, and UTC timestamp for every manual change.
- [ ] Implement a dedicated paginated staff inventory page with product/SKU search, category filter, exact quantities, last changer/time, and inline non-negative integer updates.
- [ ] Require each inline update to carry the version/current quantity the employee saw; reject stale writes with a clear conflict showing the latest quantity rather than overwriting it.
- [ ] Apply authorization, validation, CSRF, and consistent full-page/HTMX error behavior to inventory reads and writes; record the inventory audit trail in the same successful transaction as each quantity update.
- [ ] Verify filtering/pagination, integer/boundary validation, last-change display, audit contents, unauthorized access, CSRF handling, and two-editor optimistic-lock conflicts with PostgreSQL integration and MVC tests.

**Completion outcome:** Staff can safely maintain the single SKU quantity field with a complete change trail, and concurrent manual edits cannot silently lose data.

## Phase 6 — Persistent cart and checkout review

**Depends on:** Phase 4 effective pricing/catalog and Phase 5 current inventory.

- [ ] Add migrations for carts, cart items, and one-time checkout reviews/tokens, including positive quantities, unique cart/SKU lines, at most one `ACTIVE` cart per customer, `ACTIVE`/`COMPLETED` status constraints, the 500-line limit enforced by service/locking, token hashes, and reviewed price/VAT fingerprints.
- [ ] Implement lazy creation and cross-session reuse of the customer's active cart; complete carts are immutable and the next edit creates a new active cart.
- [ ] Build customer-only add/update/remove flows that merge duplicate SKU additions, accept only positive integers, reject inactive or currently out-of-stock SKUs, enforce 500 distinct lines, and derive customer ownership exclusively from the authenticated principal.
- [ ] Render cart lines using current effective prices, VAT rates, gross values, and stock without reserving inventory; clearly identify catalog changes that require removal or correction.
- [ ] Build checkout review with recalculated line/order net, VAT, and gross totals in PLN, optional purchase-order number, and a per-order Polish delivery-address form with mandatory contact/address fields and fixed country. Do not expose billing data or add an address book, comment, or second reference field.
- [ ] Revalidate current SKU activity, price, VAT, and availability whenever checkout is displayed; show actionable line errors and issue a new customer/cart-bound one-time token only for the exact reviewed values.
- [ ] Validate delivery and purchase-order input server-side, preserve safe form input after correction, and invalidate/review again when cart contents or reviewed prices/VAT change.
- [ ] Verify cart persistence, one-active-cart and line uniqueness races, ownership/IDOR protection, 500-line boundary, no stock reservation, repricing/inactivation/stock-change messages, calculation accuracy, address rules, excluded fields, token binding, and HTMX/CSRF behavior.

**Completion outcome:** Each customer has a secure persistent cart and can reach an accurate, Polish checkout review with a validated delivery address, optional purchase-order number, and a one-time token bound to the reviewed commercial data.

## Phase 7 — Atomic order submission, immutable history, and customer confirmation

**Depends on:** Phase 6 checkout token/review and Phase 5 inventory concurrency.

- [ ] Add migrations for yearly public-order counters, orders, immutable order items, billing snapshots, and delivery snapshots, with unique public numbers, a nullable non-unique customer purchase-order number, `PLN` totals, intake-status constraints, required timestamps, complete specified snapshot fields, and a maximum of 500 order lines.
- [ ] Implement concurrency-safe `ORD-YYYY-NNNNNN` allocation and the order aggregate so only intake status/update metadata can change after submission; do not expose mutation or cancellation operations to customers.
- [ ] Implement one transaction that locks/claims the active cart and token, verifies the active customer, re-resolves SKU activity/effective prices/VAT, secures stock in a deterministic SKU order, verifies quantities, creates the order/items and billing/delivery snapshots, reduces stock, completes the cart, consumes the token, and commits a `SUBMITTED` order visible to staff queries.
- [ ] Reject stale reviewed prices or VAT before order creation and return the customer to a newly calculated review; reject insufficient concurrent stock with exact current quantities. No failure path may create a partial order, consume a usable token, complete the cart, or make stock negative.
- [ ] Ensure replayed or concurrently submitted checkout tokens return the already-created confirmation where ownership matches, or a safe consumed-token result, and can never create a second order.
- [ ] Build the post-commit confirmation page with public order number and optional purchase-order number, then provide paginated customer order history/detail pages scoped to the authenticated customer and showing status and immutable commercial/delivery snapshots without internal company/billing data.
- [ ] Protect historical integrity from later customer, product, SKU, attribute, image, price, VAT, or address changes through snapshot-only rendering and persistence-level immutability checks.
- [ ] Verify exact calculations/snapshots, number uniqueness, customer history isolation, hidden billing data, token replay, rollback behavior, cart rollover, inactive/repriced items, and real PostgreSQL concurrent submissions where only stock-securing transactions succeed.

**Completion outcome:** A reviewed cart can be submitted exactly once into an immutable order with an atomic stock reduction, safe concurrency behavior, confirmation, and customer-scoped history.

## Phase 8 — Internal order intake and reliable notifications

**Depends on:** Phase 7 accepted orders and Phase 2 active staff accounts/mail adapter.

- [ ] Add migrations for notification outbox/delivery attempts, including idempotency keys, recipient, retry state, attempt timestamps, sanitized failure details, and indexes for due work and permanent failures.
- [ ] Extend order acceptance to record idempotent notification intents atomically, signal dispatch only after commit, and keep the internal order row authoritative regardless of email outcome.
- [ ] Implement customer and staff Polish email templates. Include order/price summary, authenticated application link, and optional purchase-order number; keep billing/profile data out of customer mail, and include full delivery/contact data (but link to the application for billing data) in mail to every account that was an active `EMPLOYEE` or `ADMIN` at acceptance.
- [ ] Implement immediate after-commit delivery, bounded retry/backoff for temporary failures, idempotent sends, permanent-failure classification, and operational logging that does not leak tokens, credentials, or unnecessary customer data.
- [ ] Build the shared staff inbox with status filter, complete order detail including immutable billing and delivery snapshots, purchase-order number, totals, and notification-failure visibility for employees/admins.
- [ ] Implement and authorize only the allowed intake transitions: `SUBMITTED` to `ACKNOWLEDGED`/`CANCELLED`, and `ACKNOWLEDGED` to `CLOSED`/`CANCELLED`; make conflicting/stale transitions explicit and never restore stock on cancellation.
- [ ] Record each status transition with old/new status, actor, and timestamp in the security audit stream.
- [ ] Add a cross-surface acceptance test proving the purchase-order number appears only when supplied in checkout, confirmation, customer detail, staff inbox/detail, and both email types.
- [ ] Verify recipient selection, after-commit timing, accepted-order survival during SMTP failure, retry/idempotency behavior, permanent-failure visibility, email data boundaries, inbox authorization/filtering, state-machine conflicts, audit entries, and cancellation stock behavior.

**Completion outcome:** Staff receive and process every accepted order through the shared intake inbox, customers and all active staff are notified reliably after commit, and delivery failures remain recoverable/visible without threatening order integrity.

## Phase 9 — Security, localization, responsive UX, and release acceptance

**Depends on:** all functional phases.

- [ ] Perform a route/template/service authorization inventory and add deny-by-default tests for every role, including parameter tampering across customer profiles, carts, effective prices, checkout tokens, orders, images, inventory, and administrative actions.
- [ ] Complete the security-event coverage review for invitations, blocking/unblocking, password resets, price/VAT changes, inventory changes, and order-status changes; verify actor/time accuracy and that sensitive values and raw tokens are never audited or logged.
- [ ] Run CSRF/session/cookie checks over every state-changing full-page and HTMX flow, fixation/logout behavior, blocked/reset session invalidation, login throttling, safe redirects, output escaping, upload abuse, validation bypass attempts, and production error-page information leakage.
- [ ] Review database constraints and indexes against expected scale and query plans for catalog/inventory search, effective pricing, active cart lookup, order history/inbox, and notification retries; verify the 500-line limits and pagination prevent unbounded browser/database work.
- [ ] Finish Polish copy and validation messages, Polish date/PLN formatting, and Warsaw-zone display across every page and email while retaining UTC persistence.
- [ ] Test responsive layouts at current mobile/desktop widths and complete keyboard navigation, visible focus, semantic labels/headings, error association, contrast, and HTMX focus/announcement behavior.
- [ ] Add end-to-end server-rendered journeys for administrator bootstrap, employee/customer invitation and activation, blocked/reset access, customer setup, catalog/pricing, inventory conflict, cart/checkout, price and stock races, successful order, notification failure/retry, inbox acknowledgement/closure/cancellation, and immutable customer history.
- [ ] Run the complete verification suite from a clean database, migrate a populated pre-release database forward, review configuration/secrets and production cookie/mail/image settings, and confirm no routes or UI implement any excluded capability.

**Completion outcome:** The application satisfies the complete specification with server-enforced isolation, concurrency-safe ordering, Polish and responsive server-rendered UX, auditable staff actions, reliable notifications, clean migrations, and passing automated acceptance/security checks.
