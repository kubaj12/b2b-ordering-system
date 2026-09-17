# Implementation Plan

## Approach

Connect the existing browser security filter chain to `identity_user` through identity/access application services and a JDBC read adapter. Provide a Polish Thymeleaf login page, explicit form-processing and logout behavior, and a request filter that verifies the current account status and security version before protected requests reach CSRF, logout, authorization, or MVC. Keep the existing role annotations, localized error rendering, HTMX redirects, BCrypt encoder, and server-side servlet sessions.

The repository currently has an insert-only administrator bootstrap, but no database-backed `UserDetailsService`, account principal, login controller, or request-time account validation. `BrowserSecurityConfiguration` uses the generated Spring login page, broad anonymous subtree matchers, and `anyRequest().authenticated()`. Consequently an arbitrary authenticated principal satisfies the route gate, and account status changes are not consulted. The existing navigation already submits a Thymeleaf POST form to `/logout`; the shared layout and JavaScript already transport CSRF tokens.

Use the existing stack without dependency changes: Java 25, Spring Boot 4.1.0, and its managed Spring Security 7.1.0. Preserve the architecture rules in `ModuleArchitectureTests`: web adapters may depend on their own application boundary, application services own domain normalization, and only persistence adapters execute SQL. In particular, do not inject a JDBC adapter into security configuration or import `NormalizedEmail`/`PasswordPolicy` into a web class.

This slice implements login/logout and the `ACTIVE` request boundary. Successful-login timestamps, throttling, invitation/reset consumption, SMTP, and block/unblock commands remain their subsequent roadmap tasks. Capture and check `security_version` now so those commands can invalidate prior authentication when they rotate it. The persistent `identity_session` registry and account-wide session revocation remain part of the subsequent session-invalidation workflows; this slice uses Spring's server-side servlet session and invalidates the presented servlet session when its account snapshot becomes invalid. Do not claim registry expiry/revocation enforcement until that registry is connected. No migration is required, and V001–V003 must remain unchanged.

## Changes

### 1. Add the database-backed authentication application boundary

**Files:**

* `src/main/java/io/github/kubaj12/online_store/identityaccess/application/AuthenticationAccountStore.java` — new
* `src/main/java/io/github/kubaj12/online_store/identityaccess/application/AccountPrincipal.java` — new
* `src/main/java/io/github/kubaj12/online_store/identityaccess/application/AccountUserDetailsService.java` — new
* `src/main/java/io/github/kubaj12/online_store/identityaccess/application/AccountAccessService.java` — new
* `src/main/java/io/github/kubaj12/online_store/identityaccess/application/AccountAuthenticationProvider.java` — new
* `src/main/java/io/github/kubaj12/online_store/identityaccess/persistence/JdbcAuthenticationAccountStore.java` — new
* `src/test/java/io/github/kubaj12/online_store/identityaccess/application/AccountUserDetailsServiceTests.java` — new
* `src/test/java/io/github/kubaj12/online_store/identityaccess/application/AccountAccessServiceTests.java` — new
* `src/test/java/io/github/kubaj12/online_store/identityaccess/application/AccountAuthenticationProviderTests.java` — new
* `src/test/java/io/github/kubaj12/online_store/identityaccess/persistence/JdbcAuthenticationAccountStoreTests.java` — new

**Changes:**

1. Define `AuthenticationAccountStore` as a read-only application port with:
   * `Optional<Credentials> findCredentialsByEmail(NormalizedEmail email)`;
   * `Optional<AccessSnapshot> findAccessById(UUID accountId)`.
   Define its nested application output types with `Credentials(id, email, passwordHash, role, status, securityVersion)` and `AccessSnapshot(id, email, role, status, securityVersion)`. Use strings for persisted role/status values so web callers need no domain dependency. Validate supported roles/statuses when mapping to the principal; an unexpected value must fail closed. Override diagnostic text for `Credentials` to omit the hash and email, following `NormalizedEmail` and the bootstrap redaction conventions. The access query must not select `password_hash`.
2. Implement `JdbcAuthenticationAccountStore` with `@Repository` and `JdbcTemplate`, following the constructor injection and parameter binding in `JdbcInitialAdminAccountStore`. Query `identity_user` by exact canonical email and by UUID respectively. Select only the columns listed above. Use row mapping and an empty result to return `Optional.empty()`; a missing account is an expected result, not a JDBC exception. Do not load customer profiles, invitations, or session/token/throttle tables. Do not add update or insert methods to the existing bootstrap store.
3. Implement `AccountPrincipal` in the application package as a serializable `UserDetails` and `CredentialsContainer`. Store the UUID, canonical email, one role, status snapshot, security-version snapshot, and a nullable password-hash field. `getUsername()` returns the canonical email for navigation; expose `accountId()`, `role()`, and `securityVersion()` for identity checks and future actor/ownership resolution. Produce exactly one authority: `ROLE_CUSTOMER`, `ROLE_EMPLOYEE`, or `ROLE_ADMIN`. Do not infer an administrator role from configuration or submitted form fields, and do not give ADMIN the CUSTOMER role. `isEnabled()` and `isAccountNonLocked()` are true only for `ACTIVE`; account/credential expiry flags are true because the schema has no such lifecycle. `eraseCredentials()` clears the hash while retaining identity, authorities, and version. Give the class redacted diagnostic text and a `serialVersionUID`; avoid a record that would print its hash.
4. Implement `AccountUserDetailsService` as a Spring service implementing `UserDetailsService`. In `loadUserByUsername`, normalize and validate the supplied identity with `NormalizedEmail.of`. Malformed identities and missing accounts produce a fixed `UsernameNotFoundException` without echoing the input. Existing rows produce a fresh `AccountPrincipal`, including BLOCKED rows, so the provider's account-status checks reject them. Keep user caching disabled; never return null or fall back to an in-memory/generated user.
5. Implement `AccountAccessService.isCurrent(AccountPrincipal principal)` using `findAccessById`. Return true only when the row exists, is `ACTIVE`, and its UUID, canonical email, role, and security version match the principal. Checking role/email as well as version prevents old authorities or identity data surviving an unexpected account edit that omitted version rotation. Read committed current state on every call; do not cache account eligibility in the principal, session, application cache, or request headers.
6. Implement `AccountAuthenticationProvider` as a small application-layer subclass of `DaoAuthenticationProvider`. Spring Security 7.1 requires the `UserDetailsService` constructor argument. Supply the existing `PasswordEncoder` from `ApplicationConfiguration` (`BCrypt` 2B, cost 12), retain hidden user-not-found errors and credential erasure, and use `setAlwaysPerformAdditionalChecksOnUser(true)` so blocked-account prechecks still perform the standard password comparison. Do not register a password-upgrade service or modify hashes on login.
7. Before delegating `authenticate` to the superclass, reject missing/blank passwords and passwords that fail `PasswordPolicy.hasValidUtf8Length` with a fixed `BadCredentialsException`. This covers malformed Unicode and more than 72 UTF-8 bytes, including the nonexistent-account timing-protection path. Do not trim passwords or apply the 12-character creation rule to login. Catch malformed stored-hash argument errors from the BCrypt comparison and convert them to a generic authentication failure without retaining the raw cause.
8. Sanitize infrastructure failures at the two application service boundaries, following `InitialAdminBootstrapService.sanitizedFailure`: log only a generated reference and exception type, never the exception object/message, SQL, bind values, hash, or identity. The loader should throw a fixed `InternalAuthenticationServiceException` with no raw cause; the access service should throw a sanitized runtime failure with no raw cause. A database outage must not become `Optional.empty()` or a successful account check.

**Tests:**

* Loader unit tests: mixed-case/outer-whitespace email becomes the existing canonical value; invalid/blank/null email and unknown email fail generically; each stored role produces exactly its one authority; BLOCKED produces a disabled/locked principal; UUID/version survive credential erasure and serialization; principal/output diagnostic text never exposes the synthetic hash.
* Access-service unit tests: ACTIVE matching snapshots pass; BLOCKED, missing row, different version, changed role, and changed email fail. Verify a second call after changing a fake store observes the changed state and that access lookups never request credentials.
* Provider unit tests with a low-cost test BCrypt encoder: correct credentials succeed; wrong password, unknown identity, blocked account, blank/missing password, invalid Unicode, malformed stored hash, and overlong ASCII/multibyte passwords fail. A password at the 72-byte boundary works, and whitespace remains significant. Verify `ProviderManager` erases both token credentials and the principal hash after success. Unknown and BLOCKED failures remain distinguishable only internally, with the same browser failure contract in step 3.
* Adapter tests: use `@PostgreSqlRepositoryTest`, `@Import(JdbcAuthenticationAccountStore.class)`, and `IdentityDatabaseFixture`. Verify exact canonical lookup, missing results, UUID lookup, all role/status/version mappings, and unchanged account columns after reads. Use the established parameterized role/status matrix rather than an alternative database.
* Add captured-log tests using the existing Logback `ListAppender` pattern: synthetic failing-store messages containing hashes/passwords/SQL must not appear in logged messages, exception chains, or throwable proxies.

### 2. Enforce precise anonymous routes and current account eligibility

**Files:**

* `src/main/java/io/github/kubaj12/online_store/identityaccess/web/BrowserSecurityConfiguration.java`
* `src/main/java/io/github/kubaj12/online_store/identityaccess/web/ActiveAccountRequestFilter.java` — new
* `src/test/java/io/github/kubaj12/online_store/identityaccess/web/ActiveAccountRequestFilterTests.java` — new
* `src/test/java/io/github/kubaj12/online_store/identityaccess/web/BrowserSecurityConfigurationTests.java`
* `src/test/java/io/github/kubaj12/online_store/identityaccess/web/SecurityProbeController.java`

**Changes:**

1. Replace `ANONYMOUS_PAGE_MATCHERS` with explicit method/path rules using Spring Security 7.1 `PathPatternRequestMatcher.withDefaults().matcher(HttpMethod, path)`. Preserve both existing token representations: tokens supplied to the base endpoint through form/query data and tokens supplied as a single path segment. The anonymous allowlist is:

   | Path | Anonymous methods/dispatches |
   | --- | --- |
   | `/login` | GET, HEAD, POST |
   | `/invitations/accept` and `/invitations/accept/{token}` | GET, HEAD, POST |
   | `/password-reset` and `/password-reset/{token}` | GET, HEAD, POST |
   | Spring Boot common static-resource locations | GET, HEAD |
   | `/error` | GET, HEAD; ERROR dispatches to this exact path with any original method |

   Query parameters do not extend this allowlist. Do not retain `/invitations/accept/**`, `/password-reset/**`, or `/error/**`, and do not grant all ERROR/FORWARD dispatches anonymous access. No new real invitation/reset controllers are needed in this slice; existing test controllers prove their future route contract.
2. Keep `PathRequest.toStaticResources().atCommonLocations()`, combined with a GET/HEAD matcher. CSS, JavaScript, WebJars, and actual common-location resources remain inside the security chain and retain security headers. Do not use `web.ignoring()` or allow `/` anonymously. Common static directories are reserved for non-private bundled assets; future catalog-owned SKU image routes must remain outside these locations and require authentication.
3. For necessary container error rendering, match `DispatcherType.ERROR` AND the exact `/error` path. The existing `templates/error/{400,403,404,409,500}.html` are views, not public route subtrees. Keep the default Spring Boot error endpoint and existing safe `spring.web.error` settings. A direct anonymous GET to `/error/something` must now start login.
4. Add `ActiveAccountRequestFilter` as a `OncePerRequestFilter`. Instantiate it in the filter-chain factory and add it after `SecurityContextHolderFilter`, before `CsrfFilter` and `LogoutFilter`. Do not also annotate it as a component or expose an automatically registered servlet-filter bean; it must execute only inside this chain. Override `shouldNotFilterErrorDispatch()` and `shouldNotFilterAsyncDispatch()` to return false, and reuse the eligibility-check method from `doFilterNestedErrorDispatch()` as well as `doFilterInternal()`. The default once-per-request error/nested-error bypass must not leave a protected ERROR dispatch trusting an unchecked authenticated principal. Skip only the explicit public static/error matchers described below.
5. The filter reads the loaded authentication from `SecurityContextHolder`. Missing or anonymous authentication proceeds unchanged. Authenticated requests must have an `AccountPrincipal` and pass `AccountAccessService.isCurrent`; a generic Spring test `User`, arbitrary principal, stale version, changed authority snapshot, BLOCKED row, or missing row must not qualify. On an invalid authentication, use `SecurityContextLogoutHandler` to clear authentication and invalidate any existing servlet session, then continue the chain. The protected route gate or CSRF handler will start login; public login/recovery routes can still render after invalid authentication is removed. Never create a session merely to perform this check.
6. Skip account-store access for allowed static GET/HEAD requests and the necessary exact error endpoint/dispatch, using the same matchers as the authorization configuration. This lets bundled assets and terminal errors render even during a database outage. Do not skip validation because `HX-Request` or a history-restoration header is present, or because the request is for another public namespace while it carries authentication.
7. If the access service throws an operational failure, clear/invalidate authentication and stop before the controller or logout handler. Render the sanitized runtime failure through the MVC `HandlerExceptionResolver` bean qualified as `handlerExceptionResolver`, allowing the global `LocalizedWebExceptionHandler` to return the existing full-page/HTMX 500 contract. If resolution returns null, finish with an empty 500 response. Do not treat failure as an ACTIVE result or leak the raw JDBC exception.
8. End the authorization rules with `anyRequest().authenticated()`. Together with the preceding principal/current-account filter, this means every request outside the allowlist requires a current ACTIVE persisted account, including unknown URLs, non-public methods, and forwarded requests. Preserve `@EnableMethodSecurity`, existing `@PreAuthorize` role checks, default security headers, default CSRF protection, and session fixation protection.
9. Register `AccountAuthenticationProvider` explicitly in this chain. Replace the HTMX-only `defaultAuthenticationEntryPointFor` with the existing `BrowserAuthenticationEntryPoint` as the general authentication entry point: it already delegates ordinary requests to Spring's login redirect and returns empty 401 + `HX-Redirect` for HTMX/history transports. Keep `BrowserAccessDeniedHandler` unchanged so authenticated role/CSRF failures remain localized 403s and anonymous CSRF failures retain their existing login-navigation behavior.
10. Configure `NullRequestCache` and an explicit home-page destination after login. The selected policy is to land at `/`; do not accept `next`, `returnUrl`, a submitted URL, `Referer`, or `HX-Current-URL` as a destination. This avoids caching token-bearing URLs and makes the full-page and HTMX flows consistent. Keep form authentication and sessions; do not enable HTTP Basic, remember-me, JWT, or stateless security.

**Tests:**

* Filter unit tests: missing/anonymous authentication performs no lookup; current application principal proceeds; all invalid principal/state variants clear the security context and invalidate an existing session; no session is created when absent; CSRF/logout/controller execution happens only after this validation. Infrastructure failure stops the chain and produces a sanitized full-page or fragment 500. Static/error bypasses must not call the account service.
* Extend the MVC route matrix to exercise base/token GET and HEAD, base/token CSRF-valid POST, every bundled asset used by the layout, and the exact error endpoint. Confirm public routes with anonymous authentication actually reach their test handler or resource handler, rather than merely avoiding a redirect.
* Add negative routes for `/`, `/catalog`, `/cart`, `/orders`, `/account/password`, `/staff/customers`, `/admin/employees`, `/invitations/manage`, `/password-reset-management`, `/invitations/accept/token/extra`, `/password-reset/token/extra`, `/error/required-page`, and an unknown path. Anonymous full-page requests start login; HTMX and history-restoration requests receive empty 401 + `HX-Redirect`. Use CSRF-valid unsafe requests to isolate authentication rules from CSRF failures.
* Verify PUT/DELETE/OPTIONS requests to otherwise public form paths do not become anonymous exceptions, and non-GET/HEAD static requests are protected. Use valid CSRF where required.
* Test an ERROR dispatch to `/error` with no authentication and an original POST method; test ERROR/FORWARD dispatches to a protected probe route cannot bypass account validation. Account rejection must happen before `SecurityProbeService.call()`.
* Retain the complete role matrix and existing security-header checks. Confirm ACTIVE ADMIN has staff/admin access, while CUSTOMER access still follows the explicit customer-role annotation.
* Keep existing anonymous invitation POST CSRF behavior: without/with-invalid CSRF, ordinary requests start login and HTMX requests receive 401; with valid CSRF the test command executes. Anonymous authorization does not exempt commands from CSRF.

### 3. Implement Polish login and explicit session logout

**Files:**

* `src/main/java/io/github/kubaj12/online_store/identityaccess/web/BrowserSecurityConfiguration.java`
* `src/main/java/io/github/kubaj12/online_store/identityaccess/web/LoginController.java` — new
* `src/main/java/io/github/kubaj12/online_store/identityaccess/web/BrowserLoginSuccessHandler.java` — new
* `src/main/java/io/github/kubaj12/online_store/identityaccess/web/BrowserLoginFailureHandler.java` — new
* `src/main/java/io/github/kubaj12/online_store/identityaccess/web/BrowserLogoutSuccessHandler.java` — new
* `src/main/resources/templates/identityaccess/login.html` — new
* `src/main/resources/messages.properties`
* `src/test/java/io/github/kubaj12/online_store/identityaccess/web/BrowserFormLoginTests.java` — new

**Changes:**

1. Configure `formLogin` with `.loginPage("/login")`, `.loginProcessingUrl("/login")`, `.usernameParameter("email")`, `.passwordParameter("password")`, and the explicit success/failure handlers. POST `/login` belongs to Spring Security's authentication filter, not an MVC controller. Do not call the form-login or logout `.permitAll()` shortcuts; step 2 owns the complete method-specific allowlist.
2. Implement `LoginController` with one HTML GET handler; Spring MVC also supports HEAD through this mapping. Ordinary anonymous requests render `identityaccess/login`. Pass only booleans indicating the presence of `error` and `logout` parameters. Do not inspect/render exception messages, echo credential parameters, or introduce a password-containing form object. An already-current ACTIVE account navigates to `/` through `BrowserResponse.redirect`. An anonymous HTMX GET navigates to the complete `/login` page through `BrowserResponse.redirectHtmx`, using its standard 204 response or history-restoration 409 response; no login document is swapped into a fragment.
3. Compose the template with the existing `layout/application :: layout` pattern from `index.html` and `docs/web-ui.md`, using a null active-navigation hint. Add a labeled email input named `email`, `type="email"`, `maxlength="254"`, `autocomplete="username"`, and a labeled password input named `password`, `type="password"`, `autocomplete="current-password"`. Submit with `method="post"` and `th:action="@{/login}"` so Thymeleaf inserts the hidden CSRF token. Use `required` inputs and accessible status/error regions. Do not use password `maxlength="72"` as a substitute for UTF-8 validation or impose creation-policy `minlength` on login. Do not add `hx-post` to the normal login form; the handlers still support explicitly HTMX-submitted requests.
4. Add Polish message keys for title, heading, email/password labels, submit button, one generic invalid-login message, and logout confirmation. Use the same invalid-login text for unknown email, malformed identity, incorrect password, BLOCKED account, stale account at login completion, and provider operational failure. Do not disclose account existence or status. Keep the password input empty in every rendered page. Invitation acceptance and forgotten-password screens will be implemented in their own slices; do not add nonfunctional invitation/reset forms here.
5. `BrowserLoginSuccessHandler` must recheck `AccountAccessService.isCurrent` for the authenticated `AccountPrincipal` after password verification and before navigating. Spring has already performed session fixation protection and saved authentication by this point. If the row changed to BLOCKED, disappeared, or rotated its version/role/email during login, clear authentication and invalidate the servlet session, then invoke the generic failure handler. Operational lookup failure also clears/invalidate authentication and uses the generic login failure destination; the application service has already logged the sanitized diagnostic reference. Never issue a successful redirect while retaining invalid authentication.
6. On valid login, navigate to `/`: first call `BrowserResponse.redirectHtmx`, returning empty 204 for normal HTMX and empty 409 for history restoration; for ordinary requests use a context-relative, response-encoded 303 `Location`. Reuse that same redirect policy in the failure and logout-success handlers with fixed destinations `/login?error` and `/login?logout`. Account fields and request-supplied URL parameters must never become redirect targets. Resolve context paths consistently with `BrowserResponse` and the existing entry point.
7. `BrowserLoginFailureHandler` must clear/invalidate any pre-existing authenticated servlet session and navigate to `/login?error`. Do not use the exception-preserving behavior of `SimpleUrlAuthenticationFailureHandler`, and do not save `SPRING_SECURITY_LAST_EXCEPTION`, submitted email/password, or raw exception causes in session/flash/model attributes. The new login GET creates a fresh CSRF-bearing session as necessary.
8. Configure logout with an explicit POST `/logout` request matcher AND a predicate requiring authenticated `AccountPrincipal` authentication. Since `ActiveAccountRequestFilter` runs before `LogoutFilter`, the predicate sees only a validated ACTIVE account. This extra predicate is necessary: `LogoutFilter` normally intercepts logout before `anyRequest().authenticated()` and would otherwise make anonymous POST `/logout` another exception. Retain default `SecurityContextLogoutHandler` session invalidation/context clearing and CSRF protection, and install `BrowserLogoutSuccessHandler`.
9. Configure deletion of the actual configured servlet session-cookie name, obtained from `ServerProperties` in security configuration, falling back to `JSESSIONID` only if no name is configured. The normal name here is `B2BSESSION`, configurable through `B2B_SESSION_COOKIE_NAME`; do not hard-code only `JSESSIONID`. Keep existing production cookie validation and local HTTP defaults. `ServerProperties` is a framework configuration type and does not violate the composition-root dependency rule. Keep `templates/fragments/navigation.html`'s existing POST logout form and its `th:action` unchanged.
10. GET `/logout` must not log a user out or render Spring's generated confirmation page. An anonymous request starts login; an ACTIVE request reaches the normal missing-route handling (404). There is no MVC GET logout controller. Logout invalidates the presented session only; multi-session account-wide invalidation is a subsequent workflow.

**Tests:**

* Login-template tests: Polish layout/copy, labels and autocomplete attributes, local assets, empty password input, POST `/login` action, and hidden CSRF field. `/login?error` always renders the fixed safe message; `/login?logout` renders confirmation. Include malicious credential/error/redirect query values and prove none is echoed or used as a URL.
* Real filter-chain form tests for every role: email/password parameter names authenticate the stored identity; uppercase/outer-whitespace email works; obsolete `username` submission does not silently authenticate; correct password preserves canonical principal name, UUID, role, and version. The session security context contains erased credentials and no principal hash.
* Missing/invalid login CSRF fails before password authentication and follows the established anonymous entry-point response; valid hidden-field CSRF authenticates. Repeat using HTMX header CSRF.
* Unknown identity, BLOCKED, wrong password, invalid Unicode, and overlong passwords all use `/login?error`, preserve no authenticated session, and store no exception/credentials. Verify failure after an attempted reauthentication also clears the old session.
* Create a CSRF-bearing pre-login session and assert successful login changes its ID. A subsequent authenticated GET `/` renders the existing home page. Fetch a fresh form/token after authentication rather than assuming the pre-login CSRF token remains usable.
* Simulate a state/version change between password verification and success handling using a controllable fake application store; no successful session may remain. An operational failure at this stage also fails closed with generic copy.
* Success/failure/logout transport matrix: ordinary 303 + context-relative `Location`; HTMX empty 204 + `HX-Redirect`; history restoration empty 409 + `HX-Redirect`; no fragment HTML or unexpected `Location` for HTMX. Include a non-empty servlet context path.
* Logout: ACTIVE POST with valid CSRF clears the security context, invalidates the session, expires the configured cookie, and navigates to `/login?logout`. Missing/invalid CSRF preserves the active session and gives localized 403. Anonymous POST with valid CSRF starts login and does not execute logout success handling. BLOCKED/stale sessions cannot qualify for logout interception. GET `/logout` does not invalidate an ACTIVE session.
* After logout, a subsequent protected full-page/HTMX request must require login. Repeat the cookie-deletion test with a custom session-cookie name.

### 4. Adapt MVC fixtures to persisted-account principals and extend regressions

**Files:**

* `src/test/java/io/github/kubaj12/online_store/testsupport/BrowserMvcTest.java`
* `src/test/java/io/github/kubaj12/online_store/testsupport/SecurityTestUsers.java`
* `src/test/java/io/github/kubaj12/online_store/testsupport/BrowserSecurityTestConfiguration.java` — new
* `src/test/java/io/github/kubaj12/online_store/testsupport/InMemoryAuthenticationAccountStore.java` — new
* `src/test/java/io/github/kubaj12/online_store/identityaccess/web/BrowserSecurityConfigurationTests.java`
* `src/test/java/io/github/kubaj12/online_store/identityaccess/web/BrowserFormLoginTests.java` — introduced in step 3
* `src/test/java/io/github/kubaj12/online_store/shared/web/request/BrowserRequestHandlingTests.java`
* `src/test/java/io/github/kubaj12/online_store/shared/web/error/SharedWebRenderingTests.java`
* `src/test/java/io/github/kubaj12/online_store/shared/web/error/LocalizedWebExceptionHandlerTests.java`

**Changes:**

1. Extend `@BrowserMvcTest` to import `BrowserSecurityTestConfiguration` and the shared `LoginController` alongside its current security/web configuration. The test configuration imports the real `AccountUserDetailsService` and `AccountAccessService`, supplies an in-memory implementation of `AuthenticationAccountStore`, supplies a test-only low-cost BCrypt encoder, and enables the framework `ServerProperties` configuration binding needed by logout. Do not import the JDBC adapter or the root `ApplicationConfiguration` into MVC slices.
2. Seed the fake store with stable UUIDs and canonical `customer@example.test`, `employee@example.test`, and `admin@example.test` identities, each ACTIVE with its respective single role and security version 0. Define a synthetic test password of at least 12 characters and hash it with the test encoder. Expose explicit reset/replace/remove/fail-next-lookup operations for state-transition tests, plus access/credential lookup counters where needed. Return independent principal/credential instances so authentication credential erasure never mutates a stored hash.
3. Update `SecurityTestUsers.customer()`, `.employee()`, and `.administrator()` to use `user(AccountPrincipal)` with these stable UUID/email/role/version snapshots. Provide a small shared principal factory using the same fixture constants. Plain `user("email").roles(...)` no longer represents an eligible account. Retain a deliberately generic principal in a negative regression test proving that an authenticated non-application principal is rejected.
4. Replace `@WithMockUser` in `BrowserRequestHandlingTests`, `SharedWebRenderingTests`, and `LocalizedWebExceptionHandlerTests` with Spring Security's existing `@WithUserDetails` using the appropriate seeded email and the real loader. EMPLOYEE/CUSTOMER annotations become their corresponding fixture identity; default unrestricted test users become the seeded CUSTOMER. This avoids introducing a separate custom security-context annotation/factory.
5. Convert the two raw `@WebMvcTest(SharedWebTestController.class)` error/rendering suites to `@BrowserMvcTest(controllers = SharedWebTestController.class)` so their dependencies and account eligibility are explicit and they continue exercising the same production chain. Preserve their safe-error/logging/template assertions and role-specific navigation expectations.
6. In `BrowserRequestHandlingTests`, replace the existing anonymous `/error/required-page` 404 expectation with login navigation, and add an exact `/error` error-dispatch assertion in the security suite. Preserve all existing CSRF, localized 403, HTMX representation, history, JSON-rejection, and service-call-count checks.
7. Mutating security/form suites must reset the fake store before and after each test, in addition to resetting probe service counters. Spring caches MVC contexts, so a final BLOCKED/failing store state must not contaminate another class. Keep the non-mutating shared rendering suites on seeded default state. Do not bypass the eligibility filter or stub every account as ACTIVE merely to restore existing tests.
8. Extend account-state regressions with an authenticated principal whose backing row is blocked without changing its version: its next protected request still loses access. Then test block/version increment/unblock before the old session's next request: an old version remains invalid even though current status is ACTIVE. Test matching-version but changed-role/email and missing-user cases. Run full-page, HTMX, and history requests and verify protected services are never called.

**Tests:**

* Run all four affected existing MVC suites and `BrowserFormLoginTests` without Docker. Their rendering/service assertions must pass while the current-account gate remains enabled.
* Verify the seeded account store is queried on each authenticated protected request and that status changes between consecutive requests take effect.
* Verify the original role matrix still passes with application principals; request parameters and HTMX headers cannot supply/override status, role, account ID, or security version.
* Repeat CSRF denial with a valid ACTIVE principal to preserve localized 403 semantics, and with a stale/BLOCKED principal to verify the session is cleared before an unsafe request is processed.
* Assert anonymous missing protected URLs start login, while an ACTIVE account gets the existing Polish 404. Missing static resources may return a public 404 because their namespace is an explicitly allowed static location.

### 5. Verify authentication against real PostgreSQL and the administrator seed

**Files:**

* `src/test/java/io/github/kubaj12/online_store/identityaccess/web/BrowserAuthenticationIntegrationTests.java` — new
* `src/test/java/io/github/kubaj12/online_store/testsupport/IdentityDatabaseFixture.java`
* `src/test/java/io/github/kubaj12/online_store/testsupport/PostgreSqlDatabaseCleaner.java`

**Changes:**

1. Add a full web application integration suite with `@SpringBootTest` using the mock web environment, Boot 4.1's MVC `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, `@Tag("postgresql")`, and imports of `TestcontainersConfiguration` and `DeterministicTestConfiguration`. Do not give it the `mvc` tag: `docs/testing.md` and CI promise the MVC profile needs no Docker. Do not extend `PostgreSqlServiceTestSupport`, whose meta-annotation forces a non-web environment.
2. Reuse the deterministic configuration's verified disposable-database cleanup: make `PostgreSqlDatabaseCleaner` and its existing `clean()` method public, autowire it in this suite, and call `clean()` in `@BeforeEach`. Keep its target-verification checks intact. Reset `TestClock` and deterministic adapters alongside database cleanup. Do not use test-managed transactions for this suite, run it concurrently with other committed-state database suites, or introduce an alternative SQL cleanup routine.
3. Extend `IdentityDatabaseFixture.insertUser` with a backward-compatible overload that accepts an explicit BCrypt password hash; the existing overload keeps delegating with `TEST_PASSWORD_HASH`. Existing schema/bootstrap/audit tests must continue compiling and behaving unchanged. Authentication tests must hash a known synthetic password through the real application encoder, rather than assume the fixture's existing opaque hash has a known plaintext.
4. Bootstrap an administrator through `InitialAdminBootstrapService.bootstrap` inside a test, then exercise GET login, CSRF-valid POST login, and a protected home GET using the actual saved servlet session. Inspect the real account row before/after login and assert password/role/status/version and timestamps remain unchanged; `last_login_at` is intentionally not updated until the next roadmap task.
5. Insert real ACTIVE CUSTOMER and EMPLOYEE accounts with known hashes and verify each can establish its own canonical principal/session; insert BLOCKED accounts and verify correct credentials still cannot establish authentication. Assert exactly one application `UserDetailsService` is present and no generated/in-memory fallback user is installed in the full context.
6. Authenticate, then commit a direct fixture update of account status/security version before the next request. Verify current SQL state revokes access without restarting the app. Test ACTIVE→BLOCKED at the same version, version rotation while remaining ACTIVE, and BLOCKED/version rotation→ACTIVE before the old session is reused. Update fixture timestamps consistently with V003 chronological checks. These direct writes are test simulations of future administration/reset commands, not production account-mutation APIs.
7. Test real logout followed by protected access. Exercise full-page and HTMX/history authentication rejection with saved sessions, and verify no secrets appear in rendered error output. Use GET `/login` to obtain actual CSRF material for at least one complete journey; security test `csrf()` may be used for the remaining focused cases.

**Tests:**

* The new suite must prove the runtime provider uses the real V003 table and the real bootstrap BCrypt hash, rather than merely trusting mocked principals.
* Assert stale/BLOCKED saved sessions are invalidated and cannot become usable again after unblock. A fresh login with the current version can succeed for an ACTIVE account.
* Assert login does not change account lifecycle data or write invitation/reset/throttle/session-registry rows in this slice.
* Run the existing bootstrap integration/startup and migration suites to confirm read-service bean discovery does not require servlet infrastructure in `WebApplicationType.NONE` contexts and does not alter insert-only bootstrap behavior.

### 6. Document the implemented browser authentication contract

**Files:**

* `docs/web-ui.md`
* `docs/configuration.md`
* `docs/identity-persistence.md`
* `docs/testing.md`
* `plan.md`

**Changes:**

1. Replace `docs/web-ui.md`'s broad anonymous subtree description with the method/path table from step 2. Document the exact error-dispatch exception, protected default, ACTIVE persisted-account check, and unchanged CSRF semantics. Describe login/logout ordinary and HTMX/history response contracts and the fixed home destination.
2. In `docs/configuration.md`, replace the statement that database-backed form login belongs to a later slice with the implemented bootstrap-login smoke-test instructions. Document `email`/`password` form parameters, configured-cookie deletion, and existing local/production cookie behavior. Do not add credentials, raw tokens, session IDs, or hashes to examples.
3. In `docs/identity-persistence.md`, update the opening description to mention the new read-only authentication services and the current account/security-version request check. Retain the future `identity_session` registry contract, explicitly distinguishing it from this slice's servlet-session validation. Document that later block/reset/password-change commands must rotate security version and connect registry revocation; login timestamp/throttle/token workflows are still pending.
4. In `docs/testing.md`, describe the application principals, real loader plus in-memory port used by MVC slices, `@WithUserDetails` fixtures, and the PostgreSQL-only full web integration suite. Keep the existing tag/profile separation and Docker expectations.
5. After implementation and required checks pass, mark only the Phase 2 form-login/logout/route-rules checkbox complete in `plan.md`. Do not mark timestamp/throttling, token flows, account administration, account-wide session invalidation, or the separate cookie/CSRF acceptance task complete on the basis of this slice.

**Tests:**

* Check documented paths, parameter names, statuses, and cookie behavior against the executable MVC/integration tests.
* Confirm the focused MVC profile remains Docker-free and the new real web/database test is selected by the PostgreSQL integration profile.
* Run `git diff --check` and review the diff for secrets, unrelated feature changes, and modifications to applied migrations.

## Verification

Use Java 25 as pinned by `pom.xml` and CI. Compile production and test sources, then run the existing focused categories:

```shell
./mvnw --batch-mode --no-transfer-progress test-compile
./mvnw --batch-mode --no-transfer-progress -Punit-tests test
./mvnw --batch-mode --no-transfer-progress -Pmvc-template-tests test
./mvnw --batch-mode --no-transfer-progress -Parchitecture-checks test
./mvnw --batch-mode --no-transfer-progress -Ppostgresql-integration-tests test
./mvnw --batch-mode --no-transfer-progress -Pmigration-checks test
./mvnw --batch-mode --no-transfer-progress test
git diff --check
```

The PostgreSQL, migration, and complete-suite commands require working Docker and use `postgres:18.4-bookworm`. The prior slice's implementation summary records unavailable Docker in its environment; do not treat compiled integration tests as executed tests. If Docker is unavailable, complete compilation/unit/MVC/architecture verification and report the unexecuted database checks explicitly.

Review the actual filter order: security context load → current-account validation → CSRF → eligible POST logout → username/password authentication → default authenticated authorization → MVC/method security. Ensure the custom filter has no independent servlet registration. Verify the exact route allowlist and confirm no Spring generated login/logout page or default user remains. The account check authorizes against committed state at request admission; future critical application commands must still enforce their own transactional account/ownership rules when coordinating with concurrent blocking.

With the local database and a secret-configured bootstrap administrator, manually verify: anonymous home opens the Polish login page; login has a hidden CSRF token; valid login rotates the session ID and reaches home; ADMIN sees existing staff/admin navigation; incorrect or blocked login has the same safe Polish message; logout uses POST, invalidates the session, and expires the configured cookie; a protected request afterward requires login. Verify static assets load before login and terminal errors do not loop back through authentication. On an HTTPS production-configured run, inspect the existing session cookie for Secure, HttpOnly, and SameSite=Lax; preserve local HTTP usability.

Review the final implementation against these requirements: only the explicit login/acceptance/reset/static/error exceptions are anonymous; every other request has a current ACTIVE database-backed principal; account-state/version changes are seen on subsequent full-page/HTMX/history requests; generic principals and stale sessions fail closed; role checks still precede application-service execution; all unsafe browser forms retain CSRF; login/logout redirects are fixed and context-aware; sensitive credentials/hashes/exceptions never reach HTML, session diagnostics, or logs. Ensure no applied migration, unrelated module, public API, or later invitation/reset/throttling workflow was added.

## Open Questions

None.
