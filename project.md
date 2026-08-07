# Private Customer E-Commerce Application

## 1. Purpose of This Document

This file contains the stable, project-level context for the application: its purpose, scope, architectural decisions, technology stack, security principles, core domain model, and development conventions.

This file **must not contain an implementation roadmap, task list, milestones, delivery phases, or feature schedule**. All implementation planning belongs in [`plan.md`](./plan.md).

## 2. Project Summary

The project is a private e-commerce web application intended only for selected customers. It is not a public storefront and should not allow anonymous users to browse the product catalog, cart, orders, or administrative functionality.

The application will allow authorized customers to:

- activate an account created through an invitation;
- sign in and sign out;
- browse products and product variants;
- view current prices and product availability;
- add, update, and remove items in a shopping cart;
- submit an order;
- view their own order history and order details;
- manage basic account information where applicable.

The application will allow authorized administrators to:

- create and manage customer invitations;
- activate, block, or otherwise manage customer accounts;
- manage products, variants, prices, and inventory;
- view and manage orders;
- access administrative views unavailable to customers.

The primary interface will be a server-rendered web application. A separate React single-page application is not part of the selected architecture.

## 3. Scope Boundaries and Non-Goals

The following items are outside the current project scope unless this document is explicitly updated:

- AI assistants, AI recommendations, or other AI integrations;
- a public storefront accessible without authentication;
- search-engine optimization for publicly indexed product pages;
- a React SPA as the primary frontend;
- a public REST API for third-party consumers;
- a mobile application;
- a microservices architecture;
- JWT-based browser authentication;
- implementation schedules, task breakdowns, milestones, and release plans;
- a specific online payment provider or payment-processing workflow.

The application may expose small internal HTTP endpoints required by HTMX or administrative operations, but the main interface is HTML rendered by Spring MVC and Thymeleaf.

## 4. Confidentiality and Secret-Handling Rules

This file and all other version-controlled documentation **must not contain confidential or secret values**.

Never place any of the following in this file or commit them to the repository:

- API keys;
- access tokens or refresh tokens;
- passwords;
- database credentials;
- session secrets;
- private keys or certificates containing private key material;
- SMTP credentials;
- invitation tokens copied from a real environment;
- production-only secret configuration;
- personal customer data;
- any other credential or value that grants access to an external system.

Secrets must be provided through environment variables, a local untracked configuration file, or a dedicated secret-management solution. Files containing real secrets must be excluded from version control.

An example configuration file may contain variable names and safe placeholders, for example:

```properties
DB_URL=jdbc:postgresql://localhost:5432/shop
DB_USERNAME=replace-me
DB_PASSWORD=replace-me
```

Logs, exception messages, test fixtures, screenshots, and documentation must also avoid exposing passwords, tokens, session identifiers, personal data, or other sensitive values.

## 5. Selected Technology Stack

### 5.1 Application Platform

- **Java** using a supported LTS release selected by the project configuration;
- **Spring Boot** as the application framework;
- **Spring MVC** for request handling and server-side web flows;
- **Thymeleaf** for rendering full HTML pages and reusable HTML fragments;
- **HTMX** for partial-page updates and interactive behavior without a full SPA;
- **Bootstrap** for responsive layout and reusable UI components.

### 5.2 Security

- **Spring Security**;
- form-based login;
- server-side HTTP sessions;
- secure session cookies;
- role-based authorization;
- CSRF protection enabled for state-changing browser requests.

JWT is not required for the browser application because the frontend and backend are deployed as one server-side application and use session-based authentication.

### 5.3 Persistence

- **PostgreSQL** as the relational database;
- **Spring Data JPA** for repository abstractions;
- **Hibernate** as the JPA implementation used by Spring Boot;
- **Flyway** for versioned database schema migrations.

The database schema must be changed through Flyway migrations. Automatic destructive schema updates must not be used in production.

### 5.4 Testing and Local Infrastructure

- **JUnit 5** for automated tests;
- **Spring Boot Test** and **MockMvc** for Spring and MVC integration tests;
- **Testcontainers** with PostgreSQL for database integration tests;
- **Playwright** for a small set of critical end-to-end browser tests;
- **Docker Compose** for local supporting services such as PostgreSQL.

Mockito may be used where a focused unit test benefits from replacing an external collaborator, but tests should not mock the application's core domain behavior unnecessarily.

## 6. Architectural Style

The application will be implemented as a **modular monolith**.

It will be a single Spring Boot application and a single deployable unit, while the source code will be divided into business modules with clear responsibilities. Microservices are not justified for the initial scope.

### 6.1 High-Level Runtime Architecture

```text
Browser
   |
   | HTTPS
   v
Spring Boot application
   |-- Spring MVC controllers
   |-- Thymeleaf pages and fragments
   |-- HTMX request handlers
   |-- application and domain services
   |-- Spring Security
   |-- Spring Data JPA repositories
   |-- Flyway migrations
   |
   v
PostgreSQL
```

### 6.2 Suggested Package Structure

```text
com.example.shop
|-- security
|-- customer
|-- catalog
|-- pricing
|-- inventory
|-- cart
|-- order
|-- invitation
`-- shared
```

The final base package should use the project's real organization and application name instead of `com.example.shop`.

Each business module should use a structure similar to:

```text
catalog
|-- web
|-- application
|-- domain
`-- infrastructure
```

The responsibilities of these layers are:

- **web** — MVC controllers, request models, response/view models, form handling, and Thymeleaf-related adapters;
- **application** — use cases, transaction boundaries, orchestration, and coordination between domain objects;
- **domain** — business rules, domain entities, value objects, domain services, and domain-specific exceptions;
- **infrastructure** — JPA mappings, repository implementations, database adapters, and integrations with technical services.

This structure is a guideline, not a requirement to create empty packages. A module should contain only the layers it actually needs.

### 6.3 Dependency Rules

- Controllers must not contain business logic.
- Controllers should call application services or use-case handlers.
- Domain rules must not depend on MVC, Thymeleaf, HTTP sessions, or database-specific classes.
- A module must not directly use another module's database repository as a shortcut.
- Cross-module operations should go through an application-level interface or service owned by the relevant module.
- Infrastructure code may depend on the domain and application layers, but the domain must not depend on infrastructure.
- Shared code should be genuinely cross-cutting and must not become a collection of unrelated utilities.

## 7. Web Interface Approach

The default response from the web layer is HTML rendered by Thymeleaf.

HTMX should be used where partial-page interaction improves usability, including:

- adding a product to the cart without reloading the entire page;
- changing quantities in the cart;
- updating the cart summary;
- product filtering and search;
- pagination or loading additional results;
- updating an order status in an administrative view;
- loading and submitting modal forms.

HTMX endpoints should normally return a Thymeleaf fragment rather than JSON. Full-page navigation must still work for the main user flows, and JavaScript should enhance the interface rather than become the only way to complete a critical operation.

Small amounts of plain JavaScript may be used for behavior that cannot be expressed cleanly with HTML and HTMX. Additional frontend frameworks should not be introduced without a clear need.

Bootstrap should provide the base responsive layout, forms, tables, navigation, alerts, and modal components. Custom CSS should be kept focused on project-specific presentation.

## 8. Access and Account Model

The application is invitation-only.

A recommended account activation flow is:

1. An administrator creates an invitation for a specific email address.
2. The system generates a cryptographically random, single-use, time-limited token.
3. The customer receives an activation link.
4. The customer opens the link and sets a password.
5. The account becomes active.
6. The invitation token is invalidated and cannot be used again.

The raw invitation token should not be stored in the database. A secure hash of the token should be stored and compared when the invitation is redeemed.

All application pages must require authentication except for explicitly permitted endpoints such as:

- the login page;
- login form submission;
- invitation acceptance;
- password setup or password reset pages, if implemented;
- static assets required to render those pages;
- health endpoints that are intentionally exposed and appropriately restricted.

A hidden or difficult-to-guess URL is not an access-control mechanism. `robots.txt` may reduce indexing but must never be treated as security.

### 8.1 Initial Roles

- **CUSTOMER** — may access the catalog, cart, their own profile, and their own orders;
- **ADMIN** — may manage invitations, accounts, catalog data, inventory, and orders.

A future `SALES` role may be added only when a concrete business need and its permissions are defined.

### 8.2 Account Status

The account model should support these statuses:

```text
INVITED
ACTIVE
BLOCKED
EXPIRED
```

The exact transition rules belong in domain logic. Authorization must check both the user's role and whether the account is allowed to sign in or perform the requested action.

## 9. Security Requirements

- Authentication and authorization must be enforced on the server.
- Passwords must be stored only as hashes produced by an adaptive password encoder supported by Spring Security.
- Session identifiers must be transported in cookies configured with appropriate `HttpOnly`, `Secure`, and `SameSite` attributes for the environment.
- CSRF protection must remain enabled for browser-based state-changing requests.
- Administrative routes must require an administrative role.
- Customers must be able to view and modify only their own cart, account data, and orders.
- User-controlled identifiers must never be trusted without an authorization check.
- Login and invitation endpoints should be protected against abuse, including reasonable rate limiting where appropriate.
- Sensitive values must not be included in logs.
- Error pages must not expose stack traces or internal implementation details in production.
- Security-sensitive events should be logged in a privacy-conscious manner, including failed sign-ins, account blocking, invitation redemption, and administrative changes.
- Multi-factor authentication is recommended for administrator accounts if the application is exposed beyond a tightly controlled internal network.

## 10. Core Domain Model

The following entities represent the initial domain model. Field names are illustrative and may be refined while preserving the stated responsibilities and invariants.

### 10.1 Customer

Represents the business/customer profile associated with store activity.

Typical data:

- `id`;
- display name or company name;
- contact email;
- optional phone number;
- billing or delivery information when required;
- account-related preferences;
- creation and update timestamps.

A customer profile should not contain authentication credentials.

### 10.2 UserAccount

Represents authentication and authorization data.

Typical data:

- `id`;
- normalized login email;
- password hash;
- account status;
- assigned roles;
- optional link to a `Customer` profile;
- last successful login timestamp;
- creation and update timestamps.

Email uniqueness and normalization rules must be enforced consistently.

### 10.3 Role

Represents an authorization role or permission grouping.

Initial roles are `CUSTOMER` and `ADMIN`. This may be modeled as an enum or a database-backed entity, depending on whether roles need to be configurable.

### 10.4 Product

Represents a product offered in the catalog.

Typical data:

- `id`;
- product name;
- description;
- active/visible flag;
- optional category or classification;
- creation and update timestamps.

A product may have one or more variants.

### 10.5 ProductVariant

Represents a purchasable version of a product, such as a size, package, color, or configuration.

Typical data:

- `id`;
- parent product;
- unique SKU;
- variant name or attributes;
- active flag;
- creation and update timestamps.

Cart and order items should reference a concrete product variant rather than only the parent product.

### 10.6 Price

Represents the current or effective price of a product variant.

Typical data:

- `id`;
- product variant;
- monetary amount stored as a decimal value;
- ISO currency code;
- effective-from timestamp;
- optional effective-until timestamp.

Floating-point types must not be used for monetary amounts. The application must calculate prices and totals on the server.

### 10.7 Inventory

Represents inventory information for a product variant.

Typical data:

- `id`;
- product variant;
- available quantity;
- optional reserved quantity;
- version field for concurrency control;
- update timestamp.

The system must revalidate availability during order submission rather than trusting values previously displayed in the browser.

### 10.8 Cart

Represents the current shopping cart of a customer.

Typical data:

- `id`;
- owning customer;
- cart status;
- creation and update timestamps.

The application should define whether a customer may have only one active cart. That invariant should be enforced by both domain logic and an appropriate database constraint where practical.

### 10.9 CartItem

Represents a product variant and quantity in a cart.

Typical data:

- `id`;
- parent cart;
- product variant;
- quantity;
- creation and update timestamps.

The server must validate that quantities are positive and within permitted limits.

### 10.10 Order

Represents an order submitted by a customer.

Typical data:

- `id`;
- public order number distinct from the database identifier;
- owning customer;
- order status;
- currency;
- subtotal and total values;
- billing or delivery snapshot when required;
- submitted timestamp;
- creation and update timestamps.

Order state changes must follow explicitly defined domain rules. Customers may view only their own orders.

### 10.11 OrderItem

Represents an item recorded as part of an order.

Typical data:

- `id`;
- parent order;
- source product variant identifier;
- SKU snapshot;
- product and variant name snapshots;
- unit-price snapshot;
- quantity;
- line total.

Order items must preserve the commercial data used when the order was submitted. Historical order data must not change merely because a product name or current price changes later.

### 10.12 Invitation

Represents a single-use invitation to activate an account.

Typical data:

- `id`;
- invited email address;
- hashed token;
- expiration timestamp;
- redemption timestamp;
- invitation status;
- administrator who created the invitation, when auditing is required;
- creation timestamp.

An invitation must be single-use, expire after a defined period, and become invalid after successful redemption or administrative revocation.

### 10.13 Optional Address Model

If physical delivery or formal billing data is required, address information should be modeled explicitly rather than stored as an unstructured string. Active customer addresses may be normalized, while an immutable snapshot should be stored on an order when historical accuracy is required.

## 11. Important Domain Rules

- Product prices and inventory values displayed by the browser are informational until the server validates them during a state-changing operation.
- The server is the only authority for line totals, order totals, currency, inventory validation, and order state transitions.
- A cart item quantity must be greater than zero.
- An inactive product or variant cannot be newly added to a cart.
- Order submission must revalidate product status, price, and available inventory within an appropriate transaction boundary.
- Order item pricing and descriptive data must be stored as snapshots.
- Invitation tokens must be random, time-limited, single-use, and stored only in hashed form.
- Blocked or expired accounts must not be able to create or modify store data.
- Customer-scoped data must always be queried or authorized using the authenticated customer's identity.
- Database constraints should reinforce important invariants such as unique normalized emails, unique SKUs, unique public order numbers, and valid item relationships.

## 12. Persistence Conventions

- Use Flyway for every schema change.
- Keep migration files immutable after they have been shared or applied outside a developer's local disposable database.
- Use explicit indexes for frequently queried foreign keys, normalized email, SKU, order number, status, and relevant timestamps.
- Use `BigDecimal` for monetary values and store a currency code with monetary aggregates.
- Use timestamps consistently and store them in a timezone-safe form.
- Use optimistic locking where concurrent updates could otherwise overwrite inventory, carts, or order changes.
- Avoid exposing JPA entities directly to MVC forms or templates when doing so would couple the web layer to persistence details.
- Use request/form models and view models where they improve validation, security, or clarity.

## 13. Transactions and Consistency

Application services should define transaction boundaries for business operations.

Order submission is a consistency-sensitive operation. At minimum, it must:

- load the authenticated customer's active cart;
- validate ownership and account status;
- validate that all variants remain purchasable;
- obtain authoritative current prices;
- validate inventory;
- calculate totals on the server;
- create an order and immutable order-item snapshots;
- apply the chosen inventory update or reservation rule;
- complete atomically or fail without leaving a partially created order.

The exact inventory reservation strategy may be documented separately once the business rule is selected, but correctness under concurrent requests is required.

## 14. Validation and Error Handling

- Validate form input using Jakarta Bean Validation and domain-level checks.
- Treat browser input as untrusted.
- Return clear validation messages without revealing internal details.
- Use domain-specific exceptions for expected business failures such as unavailable stock or an expired invitation.
- Map expected exceptions to appropriate user-facing pages or fragments.
- Provide a correlation identifier in server logs for unexpected errors where practical.
- Do not display stack traces, SQL statements, credentials, or internal class names to end users in production.

## 15. Testing Expectations

Automated tests should cover business rules and the most important user journeys.

### 15.1 Unit Tests

Unit tests should focus on domain behavior such as:

- account status transitions;
- invitation validity and single-use behavior;
- cart quantity rules;
- price and total calculations;
- allowed order status transitions;
- authorization-related domain decisions where applicable.

### 15.2 Integration Tests

Integration tests should use PostgreSQL through Testcontainers for behavior that depends on actual database semantics, including:

- Flyway migrations;
- repository queries;
- uniqueness constraints;
- locking or concurrency-sensitive persistence;
- transactional order submission;
- Spring Security and MVC authorization behavior.

### 15.3 End-to-End Tests

A limited Playwright suite should cover the most critical browser flows:

- invitation activation;
- login and logout;
- denial of access for an unauthenticated user;
- browsing the catalog as an authorized customer;
- adding and updating cart items;
- submitting an order;
- viewing an order as its owner;
- preventing one customer from viewing another customer's order;
- administrator management of an order or account.

## 16. User Interface and Accessibility Principles

- Use semantic HTML wherever possible.
- Every form control should have an associated label.
- Critical workflows must be usable with a keyboard.
- Validation errors should be associated with the relevant fields and summarized clearly when needed.
- Do not rely on color alone to communicate status.
- Provide meaningful page titles, headings, button labels, and link text.
- Use Bootstrap's responsive patterns, but verify behavior on both narrow and wide screens.
- HTMX updates should preserve understandable focus behavior and provide appropriate loading and error feedback.
- The application should remain understandable when a partial request fails; an error must not silently leave the page in an ambiguous state.

## 17. Configuration and Environments

- Environment-specific values must be externalized from the codebase.
- Spring profiles may be used for local development, tests, and production-specific non-secret behavior.
- Real credentials must be supplied outside version control.
- Docker Compose should provide reproducible local dependencies, especially PostgreSQL.
- An `.env.example` file may list required variable names using safe placeholders, but `.env` files containing real values must not be committed.
- Test configuration must not reuse production credentials or production databases.

## 18. Logging and Auditability

The application should produce structured, useful logs without exposing secrets or unnecessary personal data.

Useful auditable events include:

- successful and failed authentication attempts;
- invitation creation, revocation, expiration, and redemption;
- account activation, blocking, and unblocking;
- administrative changes to products, prices, inventory, and orders;
- order submission and order status changes;
- unexpected failures in consistency-sensitive operations.

Audit records should identify the acting account, the type of action, the affected business object, and the timestamp where this is appropriate and lawful. Passwords, raw tokens, session identifiers, and full confidential payloads must never be logged.

## 19. Documentation Conventions

- `project.md` describes stable project context and architectural decisions.
- `plan.md` contains implementation tasks, priorities, milestones, and sequencing.
- `README.md` contains repository setup and developer onboarding instructions.
- Architecture decisions that require detailed justification may be recorded as separate Architecture Decision Records (ADRs).
- Database changes are documented by Flyway migrations and, where useful, supporting schema documentation.
- Documentation examples must use fake data and placeholders only.

## 20. Key Architectural Decisions

1. The application is private and invitation-only.
2. Authentication uses Spring Security with server-side sessions.
3. The UI is rendered by Spring MVC and Thymeleaf.
4. HTMX provides partial-page interactivity without a full SPA.
5. Bootstrap is the primary UI toolkit.
6. The backend is a modular monolith implemented as one Spring Boot application.
7. PostgreSQL is the system of record.
8. Spring Data JPA and Hibernate provide persistence access.
9. Flyway owns database schema evolution.
10. Testcontainers provides production-like PostgreSQL integration tests.
11. Docker Compose supports local infrastructure.
12. AI functionality is not part of this project.
13. Secrets and confidential data must never be stored in this file or committed to the repository.
14. Implementation planning belongs exclusively in `plan.md`.
