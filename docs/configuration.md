# Runtime configuration

The application reads deployment-specific settings from environment variables. Values in the
repository are safe development or test defaults only. Production credentials belong in the
deployment platform's secret store and must never be committed to this repository, written to a
Flyway migration, or included in logs.

Spring Boot's `production` profile enables additional startup checks. Run production deployments
with `SPRING_PROFILES_ACTIVE=production`; startup fails with property-name-only messages when a
required value or security invariant is missing.

## Local development

Running the application from the repository uses Spring Boot's Docker Compose integration. The
PostgreSQL container has a public, local-only fixture credential and is exposed on an ephemeral
host port. Spring Boot discovers that port and supplies the JDBC connection details.

The `.env.example` file is a reference, not a production configuration file. Environment variables
must be exported by the shell, IDE, container runtime, or deployment platform. If an ignored `.env`
file is used by local tooling, keep it outside version control.

By default, outbound mail delivery is disabled, the SMTP endpoint is `localhost:1025`, images use
`./var/sku-images`, the public application URL is `http://localhost:8080`, and the session cookie is
not marked `Secure` so it works over local HTTP. `HttpOnly` and `SameSite=Lax` are always enabled.

`B2B_MAIL_DELIVERY_ENABLED=false` means that notification delivery must make no SMTP connection.
The notification module will use this switch when its delivery adapter is implemented. To inspect
messages with a local SMTP catcher such as Mailpit, explicitly set delivery enabled to `true` and
keep the host and port pointed at the local catcher. Automated tests keep delivery disabled and use
a deterministic recording adapter rather than SMTP.

Tests continue to use the pinned PostgreSQL Testcontainer. Its service connection overrides
ordinary datasource properties, so tests do not depend on local or production database values.

## Environment variables

| Variable | Required in production | Purpose |
| --- | --- | --- |
| `B2B_DB_JDBC_URL` | Yes | PostgreSQL JDBC URL, for example `jdbc:postgresql://db:5432/online_store`. |
| `B2B_DB_USERNAME` | Yes | PostgreSQL application user. |
| `B2B_DB_PASSWORD` | Yes, secret | PostgreSQL application password. |
| `B2B_MAIL_DELIVERY_ENABLED` | Yes; must be `true` | Allows the future SMTP delivery adapter to make delivery attempts. |
| `B2B_MAIL_FROM_ADDRESS` | Yes | Sender address used by application mail. |
| `B2B_MAIL_HOST` | Yes | SMTP host. |
| `B2B_MAIL_PORT` | Yes | SMTP port. |
| `B2B_MAIL_USERNAME` | When SMTP authentication is enabled | SMTP user. |
| `B2B_MAIL_PASSWORD` | When SMTP authentication is enabled; secret | SMTP password. |
| `B2B_MAIL_SMTP_AUTH` | Yes | Enables SMTP authentication. |
| `B2B_MAIL_STARTTLS_ENABLED` | When implicit SSL is disabled | Enables STARTTLS. |
| `B2B_MAIL_STARTTLS_REQUIRED` | When implicit SSL is disabled | Requires successful STARTTLS negotiation and prevents plaintext fallback. |
| `B2B_MAIL_SSL_ENABLED` | As applicable | Enables implicit SMTP SSL. Production requires SSL or both STARTTLS flags. |
| `B2B_MAIL_CONNECTION_TIMEOUT_MILLIS` | No | SMTP connection timeout; defaults to 5000 ms. |
| `B2B_MAIL_READ_TIMEOUT_MILLIS` | No | SMTP read timeout; defaults to 10000 ms. |
| `B2B_MAIL_WRITE_TIMEOUT_MILLIS` | No | SMTP write timeout; defaults to 10000 ms. |
| `B2B_IMAGE_STORAGE_ROOT` | Yes | Absolute path to durable SKU image storage. |
| `B2B_BASE_URL` | Yes | Absolute public application URL; production requires HTTPS. |
| `B2B_SESSION_COOKIE_NAME` | No | Session cookie name; defaults to `B2BSESSION`. |
| `B2B_SESSION_COOKIE_SECURE` | Yes; must be `true` | Adds the `Secure` cookie attribute. |
| `B2B_BOOTSTRAP_ADMIN_ENABLED` | No | Enables the idempotent initial-administrator seed. |
| `B2B_BOOTSTRAP_ADMIN_EMAIL` | When bootstrap is enabled | Initial administrator email address. |
| `B2B_BOOTSTRAP_ADMIN_PASSWORD` | When bootstrap is enabled; secret | Initial password, at least 12 Unicode code points and at most 72 UTF-8 bytes. |

`B2B_LOCAL_DB_NAME`, `B2B_LOCAL_DB_USERNAME`, and `B2B_LOCAL_DB_PASSWORD` customize only the local
Compose container. They are not used by packaged production deployments.

## Production invariants

The production profile rejects startup unless all of the following hold:

- database URL, user, and password are present;
- the base URL uses HTTPS;
- the image-storage root is absolute;
- the session cookie is `Secure`, `HttpOnly`, and `SameSite=Lax`;
- mail delivery is enabled and either SMTP SSL or mandatory STARTTLS is enabled;
- the SMTP port is explicitly configured as an integer from 1 through 65535;
- SMTP credentials are present when SMTP authentication is enabled;
- bootstrap email and password are valid when bootstrap is enabled.

## Initial administrator bootstrap

Bootstrap is disabled by default. When enabled, the email is stripped, lowercased with the root
locale, and validated as a nonblank address no longer than 254 characters. The password must be
nonblank, contain at least 12 Unicode code points, contain valid Unicode text, and encode to no
more than 72 UTF-8 bytes. Password whitespace is significant and is never trimmed or normalized.
The password is hashed at runtime with BCrypt and only the hash is stored.

After Flyway and the datasource are ready, the application performs an insert-only conditional
seed. A new row is `ADMIN`, `ACTIVE`, security version `0`, with UTC timestamps. The database's
canonical email constraint makes restarts and concurrent startup safe. If any account already
occupies the configured normalized email, startup succeeds with a credential-free skip and does
not promote, reactivate, reset, or otherwise modify it. Changing the configured password never
rotates an existing password; changing the email selects a separate seed target and does not
rename an account.

Enable it using exported variables or deployment secret configuration, check the credential-free
created or skipped outcome, then disable it and remove the initial credentials. Enabled restarts
still require valid credentials. Verify the browser smoke journey by opening `/login`, obtaining its
CSRF field, and POSTing the configured identity with the `email` and `password` parameters. A
valid login reaches `/`; logout is an authenticated POST and expires the configured servlet session
cookie. The cookie name comes from `B2B_SESSION_COOKIE_NAME` (default `B2BSESSION`), while local
HTTP keeps `Secure=false` and production validation requires `Secure=true`, `HttpOnly=true`, and
`SameSite=Lax`.

Application logs must not contain raw passwords or hashes, token-bearing paths or links, servlet
headers or session IDs, SMTP credentials, or mail bodies. Use fixed outcomes and diagnostic
references. JDBC bind logging and SMTP debug logging stay disabled. The application pins safe
defaults for the framework JDBC, Hibernate bind, Hikari, web, security, and generated-user logger
categories; deployments must not enable credential-bearing TRACE/debug categories.
