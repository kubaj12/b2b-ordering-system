# Code Review — PH2-TASK-03, round 3

## Verdict

No new actionable functional defects found in the reviewed implementation. The remaining P2 finding from `review-02.md` is resolved, with executable regression coverage for all three browser transports. All earlier reproduced findings remain resolved.

The corrective changes are acceptable on the evidence available here. This does not establish completion of every verification item in the implementation plan: planned security regressions remain missing, and PostgreSQL/full-context verification could not be executed in this environment.

Reviewed the current worktree, including untracked implementation and test files, against `project.md`, `plan.md`, the implementation plan, implementation summary, and both previous reviews. HEAD is still `31b0ae5`; task changes are uncommitted. No implementation code or repository tests were modified during this review.

## Findings

None newly identified. The coverage and execution limitations below are carried-forward verification concerns, not newly reproduced implementation failures.

## Review-02 finding — resolved

### Filter-generated server errors retain Spring Security response headers

In [BrowserSecurityConfiguration.java:84](/Volumes/X9Pro/Projects/b2b-ordering-system/src/main/java/io/github/kubaj12/online_store/identityaccess/web/BrowserSecurityConfiguration.java:84), `ActiveAccountRequestFilter` is now inserted after `HeaderWriterFilter`. Inspection of the instantiated security chain confirmed this order:

```text
SecurityContextHolderFilter
HeaderWriterFilter
ActiveAccountRequestFilter
CsrfFilter
LogoutFilter
UsernamePasswordAuthenticationFilter
...
AuthorizationFilter
```

This preserves context loading before the persisted-account check and keeps that check ahead of CSRF, logout, form authentication, and request authorization. Spring's header writer now wraps responses terminated by the account filter. No duplicate hard-coded response-header policy was introduced.

The updated [BrowserFormLoginTests.java:69](/Volumes/X9Pro/Projects/b2b-ordering-system/src/test/java/io/github/kubaj12/online_store/identityaccess/web/BrowserFormLoginTests.java:69) verifies localized HTTP 500 output and all three missing headers for ordinary, HTMX, and history-restoration requests. The focused MVC suite passed with those assertions enabled.

Independent diagnostics using the actual MVC test context and security chain reproduced account-access lookup failures for each transport. All returned nonempty HTTP 500 HTML with:

```text
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
```

Ordinary and history responses contained the full error page; HTMX returned the error fragment with the handled-error marker. This closes the previous finding.

## Earlier fixes and regression checks

Independent Java 25 diagnostics also rechecked the following behavior against the current implementation:

- **Repository proxy startup:** the non-final `JdbcAuthenticationAccountStore` initializes successfully with Boot's actual persistence exception-translation auto-configuration and default class-proxy settings. This isolated check does not access PostgreSQL or prove complete application startup.
- **Error rendering:** account lookup failure produces a localized 500 response, not the previous empty 200 response.
- **Anonymous HTMX login:** GET `/login` returns empty 204 with `HX-Redirect: /login`; history restoration returns empty 409 with the same redirect. A `/b2b` servlet context produces `/b2b/login` correctly.
- **Current-account admission:** BLOCKED status, changed security version, changed role/email, a missing row, and a generic Spring principal reject protected access with login navigation.
- **Anonymous route boundaries:** CSRF-valid anonymous POST `/logout`, OPTIONS/PUT `/login`, multi-segment invitation/reset paths, and `/error/extra` remain protected.
- **Credential rejection:** wrong, empty, over-72-byte, and malformed-Unicode passwords use the fixed generic `/login?error` destination.
- **Successful authentication:** both authentication-token credentials and the principal's hash are erased; the pre-login session ID changes; an authenticated home request succeeds.
- **Logout:** missing CSRF returns localized 403 without invalidating the active session. Valid POST logout invalidates the session, expires the default configured `B2BSESSION` cookie, and navigates to `/login?logout`.

Source inspection confirms the account filter is still constructed only within the security-chain factory, and its eligibility bypass remains limited to public static resources and the exact necessary error endpoint. The documented anonymous method/path allowlist remains consistent with the implementation. Read-only authentication remains inside the identity/access module; no migration or later token/throttle/session-registry workflow was added.

## Remaining verification and test-coverage limitations

The coverage concerns from the previous review have not otherwise been addressed. `BrowserFormLoginTests` still has seven tests and `BrowserAuthenticationIntegrationTests` has two; dedicated loader/access-service/provider/filter tests and authentication JDBC-adapter tests from the plan remain absent.

Recommended follow-up regressions, with concrete targets:

- Extend `BrowserFormLoginTests.accountLookupFailureRendersLocalizedServerErrorAndClearsAuthentication` to authenticate into a saved `MockHttpSession`, inject the failure, and assert that session is invalidated and no authenticated context survives. Its current response assertions do not directly establish the authentication-cleanup promise in the test name. Also assert secret redaction for HTMX/history bodies, not only the ordinary response.
- Extend `BrowserSecurityConfigurationTests` with BLOCKED, version-rotated-and-unblocked, changed role/email, missing-row, and generic-principal cases across ordinary/HTMX/history transports; assert that `SecurityProbeService.calls()` stays zero. Use the existing controllable account store and reset it around mutating tests.
- Add the planned application tests for normalized identity loading, password byte/Unicode boundaries, blocked credentials, sanitized infrastructure failures, and credentials/principal diagnostic redaction. Add a login-success-handler test where the account snapshot changes between password verification and success completion and assert that authentication/session state is removed.
- Strengthen the logout regression to assert the exact configured cookie name/path and session invalidation, including a custom cookie name. Add authenticated GET `/logout` and post-logout protected-access checks. Existing independent diagnostics establish the default behavior, but are not committed regressions.
- Expand `BrowserAuthenticationIntegrationTests` to exercise bootstrap ADMIN login, real version rotation and unblock transitions using saved sessions, and the planned real-database logout journey. The current two tests do not cover those planned acceptance paths.

Docker unavailability prevents execution of database-backed checks here; it does not prevent adding the Docker-free application, handler, or MVC regressions. These limitations should remain explicit rather than treating the implementation plan's entire test matrix as completed.

## Verification performed

All Maven checks used the pinned Java 25 runtime:

```shell
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home
./mvnw --batch-mode --no-transfer-progress -Pmvc-template-tests test
./mvnw --batch-mode --no-transfer-progress -Punit-tests test
./mvnw --batch-mode --no-transfer-progress -Parchitecture-checks test
git diff --check
```

Results: **54 MVC, 81 unit, and 19 architecture tests passed**, with zero failures, errors, or skips. Maven compile/test-compile phases succeeded. Tracked whitespace checks passed.

The previous temporary diagnostics were rerun without modifying repository code/tests: `/private/tmp/ph2-review02.X6pbVO/ReviewBoundaryDiagnostics.java` and `/private/tmp/ph2-review.F0jBzu/ReviewDiagnostics.java`, using the actual current test classpath and Java 25.

`docker info --format '{{.ServerVersion}}'` failed with permission denied for `/Users/jakubjakimiuk/.docker/run/docker.sock` under the current sandbox. PostgreSQL, migration, and complete-suite tests were not run in this review. Execute `-Ppostgresql-integration-tests`, `-Pmigration-checks`, and the complete `test` suite with Java 25 and accessible Docker before claiming full acceptance verification.
