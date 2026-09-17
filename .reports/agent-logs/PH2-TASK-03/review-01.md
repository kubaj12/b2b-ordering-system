# Code Review — PH2-TASK-03

## Verdict

Changes requested. Three defects were reproduced: one prevents application startup with the repository's default configuration, and two break the existing browser/HTMX response contract. Passing focused tests do not establish completion because these paths are untested.

Reviewed the complete current task worktree, including new untracked source/test files, against `implementation-plan.md`, `plan.md`, `project.md`, and the existing security, rendering, persistence, and test conventions. The comparison baseline is HEAD `31b0ae5`. No implementation code or repository tests were modified during this review.

## Findings

### 1. [P1] The final JDBC repository cannot be proxied, preventing startup

Location: [JdbcAuthenticationAccountStore.java:14](/Volumes/X9Pro/Projects/b2b-ordering-system/src/main/java/io/github/kubaj12/online_store/identityaccess/persistence/JdbcAuthenticationAccountStore.java:14).

`JdbcAuthenticationAccountStore` is both `@Repository` and `final`. Boot 4.1's `PersistenceExceptionTranslationAutoConfiguration` creates a `PersistenceExceptionTranslationPostProcessor` with `proxyTargetClass=true` by default, controlled by `spring.aop.proxy-target-class`. The repository does not override that default. Spring therefore attempts to create a CGLIB subclass of this adapter and fails because the class is final. Implementing `AuthenticationAccountStore` does not force an interface proxy under this configuration.

This prevents the full application from initializing once persistence infrastructure is available, including the newly added PostgreSQL web tests. MVC slices pass because they replace this adapter with the in-memory store and do not load the persistence exception-translation infrastructure.

Reproduction: an isolated `ApplicationContextRunner` loaded the actual Boot 4.1 persistence exception-translation auto-configuration, a `JdbcTemplate` backed by a nonconnecting `DriverManagerDataSource`, and the new adapter. No Docker or database query was required. Context initialization failed with this cause chain:

```text
BeanCreationException: Error creating bean with name 'jdbcAuthenticationAccountStore'
AopConfigException: Could not generate CGLIB subclass ... JdbcAuthenticationAccountStore
IllegalArgumentException: Cannot subclass final class ... JdbcAuthenticationAccountStore
```

Required correction: remove `final` from this repository class, following the existing non-final `JdbcInitialAdminAccountStore`. Keep the application's existing proxy configuration; changing global AOP behavior to accommodate this class could affect other services.

Regression verification: add a Docker-free context test loading the adapter with Boot's real exception-translation auto-configuration and default proxy settings, and assert successful initialization and an `AuthenticationAccountStore` bean. Then run the actual PostgreSQL/full-context suites in a Docker-enabled environment. Compilation and in-memory MVC tests alone cannot detect this defect.

### 2. [P2] Account-lookup failures discard the error view and return empty HTTP 200

Location: [ActiveAccountRequestFilter.java:77](/Volumes/X9Pro/Projects/b2b-ordering-system/src/main/java/io/github/kubaj12/online_store/identityaccess/web/ActiveAccountRequestFilter.java:77).

The exception branch calls `exceptionResolver.resolveException(...)`, checks only whether its return value is null, and then returns from the filter. For this application's `LocalizedWebExceptionHandler`, the resolver returns a nonempty `ModelAndView` containing the safe error model and HTTP 500 status. It does not render that view into the response. Rendering and applying the model's status normally happen later in `DispatcherServlet`, which this filter never invokes on the failure path.

The distinction matters because `BrowserResponse.render` sets representation/HTMX headers but stores the HTTP status in the returned `ModelAndView`; it does not set that status directly on `HttpServletResponse`.

Reproduction through the actual MVC slice and production security chain:

1. Set `InMemoryAuthenticationAccountStore.failNextLookup(...)`.
2. Request protected `/` using `SecurityTestUsers.customer()`.
3. Repeat with `HX-Request: true`.

Observed responses:

```text
Ordinary: status=200, bodyLength=0, contentType=null
HTMX:     status=200, bodyLength=0, X-B2B-Handled-Error=true
```

The log records a 500 and authentication is cleared, but the client receives a successful empty response. On HTMX, the exception handler also sets the main-content retarget headers, so the empty response can erase the visible page instead of displaying the localized error. Availability monitoring would incorrectly count the ordinary response as successful.

Required correction: retain and render the resolved `ModelAndView` when it contains a view. Apply its status, resolve the Thymeleaf view using the request's locale, and render its model into the response. Reuse the explicit status/view-rendering pattern already implemented in `BrowserAccessDeniedHandler`, preferably through a shared rendering helper if needed. Distinguish an empty `ModelAndView` indicating an already-handled response from a nonempty view requiring rendering. Preserve a safe 500 fallback when resolution fails, without rethrowing credential-bearing causes.

Regression verification: add failure-path tests for ordinary, HTMX, and history-restoration protected requests. Assert HTTP 500, nonempty Polish error HTML, the appropriate full-page/fragment representation, safe error-reference output, no raw exception details, cleared/invalidated authentication, and zero downstream controller/service calls. These tests require only the existing fake store and MVC configuration, not Docker.

### 3. [P2] Anonymous HTMX login GET selects a nonexistent fragment instead of navigating

Location: [LoginController.java:36](/Volumes/X9Pro/Projects/b2b-ordering-system/src/main/java/io/github/kubaj12/online_store/identityaccess/web/LoginController.java:36), with [login.html:5](/Volumes/X9Pro/Projects/b2b-ordering-system/src/main/resources/templates/identityaccess/login.html:5).

For an anonymous HTMX request, the controller calls `BrowserResponse.render` with `identityaccess/login :: content`. The template defines neither a `content` fragment nor a matching `content` element; its login section is `id="page-content"`. Thymeleaf consequently renders no selected content.

Reproduction through the actual MVC slice:

```text
GET /login + HX-Request: true
status=200, bodyLength=0, HX-Redirect absent
```

An anonymous history-restoration request also returns HTTP 200 with the full login document and no `HX-Redirect`, rather than the planned full-navigation response. The implementation plan explicitly requires anonymous HTMX login GETs to navigate to the complete login page, so simply adding the missing fragment would still diverge from the chosen behavior.

Required correction: after the already-authenticated-account branch, handle `request.htmxTransport()` with `BrowserResponse.redirectHtmx(request, response, "/login")` and return without rendering. Its existing implementation provides empty 204 + `HX-Redirect` for ordinary HTMX and empty 409 + `HX-Redirect` for history restoration. Render the login document only for ordinary requests. Remove the unused nonexistent fragment selector or ensure it can never be selected.

Regression verification: add anonymous `/login`, `/login?error`, and `/login?logout` tests across ordinary, HTMX, and history transports. Ordinary requests must render the complete Polish form; HTMX/history requests must have the correct status, empty body, and context-aware `HX-Redirect`. Include a nonempty servlet context path and ensure no `Location` header is emitted for the HTMX cases.

## Implementation and plan coverage

The implementation follows the main ports-and-adapters design: SQL is behind an application port, normalization stays in the application/domain boundary, principals carry persisted UUID/role/version, request eligibility compares current persisted state, and the existing BCrypt encoder, CSRF rules, localized 403s, and role annotations are retained. An additional review diagnostic confirmed that an ACTIVE row with a newer security version rejects an older principal with a 302 redirect to `/login`.

The deliberate deferral of login timestamps, throttling, token flows, and the persistent session registry matches the plan. Those deferred features are not findings for this task.

However, much of the specified verification was omitted. The new form suite has only four tests, and the PostgreSQL web suite has only two. No new loader, access-service, provider, filter, or JDBC-adapter test class was added. The existing security probe matrix was not extended to cover the new account-state and dispatcher boundaries. In particular, these required behaviors remain without automated regression coverage:

* BLOCKED and missing backing rows, changed role/email, generic authenticated principals, version rotation followed by unblock, repeated current-state reads, and rejection before protected application-service execution across full-page/HTMX/history transports.
* Password byte/Unicode boundaries, malformed stored hashes, wrong/missing passwords, credential erasure in the saved security context, actual session-ID rotation, and state changes between password verification and the login-success check.
* Login/logout CSRF failures, failed reauthentication cleanup, post-logout rejection using the previously authenticated session, custom cookie names, and cookie expiration. Despite its name, `logsOutOnlyThroughPostAndExpiresConfiguredSessionCookie` never asserts a cookie or session invalidation; its final GET `/logout` is an unrelated anonymous request.
* HEAD and unsupported-method route rules, multi-segment token-path denial, the exact ERROR exception, protected ERROR/FORWARD dispatches, and context-path behavior.
* Real bootstrap ADMIN login, all persisted roles/statuses, version changes in PostgreSQL, and absence of a generated fallback identity.

Docker unavailability explains why database tests could not execute; it does not explain omitting the Docker-free service/filter/MVC tests. Add the focused regressions above, including those attached to each finding, before considering the slice complete.

Two smaller plan deviations should also be reconciled:

* [BrowserSecurityConfiguration.java:60](/Volumes/X9Pro/Projects/b2b-ordering-system/src/main/java/io/github/kubaj12/online_store/identityaccess/web/BrowserSecurityConfiguration.java:60) exposes `ActiveAccountRequestFilter` as a `Filter` bean without disabling servlet registration, contrary to the plan's explicit security-chain-only instruction. Instantiate it inside the chain factory or provide a disabled `FilterRegistrationBean` so its lifecycle/order is owned solely by the security chain.
* [ActiveAccountRequestFilter.java:58](/Volumes/X9Pro/Projects/b2b-ordering-system/src/main/java/io/github/kubaj12/online_store/identityaccess/web/ActiveAccountRequestFilter.java:58) skips the entire anonymous matcher, including login and recovery forms, whereas the plan limits account-check bypass to static assets and necessary errors. This leaves invalid authentication attached to public form requests. Split the eligibility-bypass matcher from the anonymous authorization matcher and verify that public forms remain accessible after invalid authentication is cleared.

Finally, `docs/web-ui.md:156` retains the old anonymous `/**` namespace description immediately before the new narrower table. Replace the old paragraph as the plan requested so the documentation has one consistent route contract. The completed checkbox in `plan.md` should not be treated as evidence that startup and the required security regressions passed.

## Verification performed

* `./mvnw --batch-mode --no-transfer-progress -Pmvc-template-tests test` — passed: 51 tests, zero failures/errors. This invocation used the environment's default Java 26.
* `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home ./mvnw --batch-mode --no-transfer-progress -Punit-tests test` — passed: 81 tests, zero failures/errors.
* `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home ./mvnw --batch-mode --no-transfer-progress -Parchitecture-checks test` — passed: 19 tests, zero failures/errors.
* `git diff --check` — passed for tracked changes.
* Isolated Java 25 diagnostics against the project's actual test classpath reproduced all three findings. Repository startup was checked with Boot's real persistence exception-translation auto-configuration; web failures were checked through the existing `BrowserFormLoginTests` application context and its real `MockMvc` security chain. Diagnostic code was created only in `/private/tmp/ph2-review.F0jBzu/ReviewDiagnostics.java`.
* `docker info --format '{{.ServerVersion}}'` — could not access Docker: permission denied for the configured Unix socket under the current sandbox. PostgreSQL, migration, and complete-suite execution were not performed in this review. No claim is made that real database/startup journeys passed.

After correcting the findings and adding the missing focused regressions, run all focused profiles and the complete suite with Java 25 and working Docker. In particular, require successful full-context initialization and the real bootstrap-login/logout journey; the current passing MVC/architecture checks exclude the adapter that causes finding 1.
