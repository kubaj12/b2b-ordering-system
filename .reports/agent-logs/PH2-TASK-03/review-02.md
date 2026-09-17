# Code Review — PH2-TASK-03, round 2

## Verdict

Changes requested for one remaining P2 finding. All three functional findings from `review-01.md` are resolved and were independently rechecked. The smaller filter-registration, eligibility-bypass, and documentation inconsistencies identified there are also corrected.

Reviewed the current task worktree, including untracked implementation/test files, against the implementation plan, previous review, and project security conventions. HEAD remains `31b0ae5`; the task changes are still uncommitted. No implementation source or repository tests were modified during this review.

## Remaining finding

### 1. [P2] Filter-generated server errors bypass Spring's security response headers

Location: [BrowserSecurityConfiguration.java:83](/Volumes/X9Pro/Projects/b2b-ordering-system/src/main/java/io/github/kubaj12/online_store/identityaccess/web/BrowserSecurityConfiguration.java:83), together with [ActiveAccountRequestFilter.java:86](/Volumes/X9Pro/Projects/b2b-ordering-system/src/main/java/io/github/kubaj12/online_store/identityaccess/web/ActiveAccountRequestFilter.java:86).

The account filter is inserted immediately after `SecurityContextHolderFilter`, before `HeaderWriterFilter`. On an account-access infrastructure failure, it clears authentication, renders the newly corrected 500 response, and returns without invoking the remaining filter chain. `HeaderWriterFilter` therefore never wraps or processes this response.

The rendered page now has the correct status and content, but loses the default response protections used elsewhere in the application. This contradicts the plan's requirement to retain Spring Security's browser response headers and the shared no-store boundary. This is a newly identified residual issue; the earlier empty-200 error-rendering defect itself is fixed.

Reproduction used the actual `BrowserFormLoginTests` context, real security chain, and the fake account-store failure control:

1. Call `accounts.failNextLookup(new IllegalStateException(...))`.
2. Request protected `/` with `SecurityTestUsers.customer()`.
3. Repeat with `HX-Request: true` and `HX-History-Restore-Request: true`.

All three transports returned localized, nonempty HTTP 500 responses, but these headers were absent:

```text
Cache-Control: absent
X-Content-Type-Options: absent
X-Frame-Options: absent
```

For comparison, an ordinary `/login` response through the same context returned:

```text
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
```

The actual chain order confirms the cause:

```text
SecurityContextHolderFilter
ActiveAccountRequestFilter
HeaderWriterFilter
CsrfFilter
LogoutFilter
UsernamePasswordAuthenticationFilter
...
```

Required correction: insert `ActiveAccountRequestFilter` after `HeaderWriterFilter` instead. This still loads the security context before eligibility checking and keeps eligibility checking before CSRF, logout, authentication processing, and authorization. Let Spring's header writer apply the configured policy; do not duplicate a hard-coded header list in the error renderer. This adjusts the original plan's placement recommendation to account for responses terminated inside the account filter.

Regression test: extend `BrowserFormLoginTests.accountLookupFailureRendersLocalizedServerErrorAndClearsAuthentication` to cover ordinary, HTMX, and history-restoration requests and assert HTTP 500, nonempty safe HTML, `Cache-Control` containing `no-store`, `X-Content-Type-Options: nosniff`, and `X-Frame-Options: DENY`. Retain the fragment/history representation checks, authentication invalidation, and redaction assertions. Assert the relative filter order if adding a configuration-level test: context loading → header writer → account eligibility → CSRF/logout.

## Previous review findings — resolved

* **Final JDBC repository / startup proxy failure:** `JdbcAuthenticationAccountStore` is now non-final. The same isolated context using Boot 4.1's actual `PersistenceExceptionTranslationAutoConfiguration` and default class-proxy settings initializes successfully. This verifies the proxy correction without making a claim about an unexecuted full database startup.
* **Discarded error view / empty 200:** the filter now applies the resolved `ModelAndView` status and renders its Thymeleaf view. Independent checks returned ordinary HTTP 500 with 2,474 bytes, HTMX HTTP 500 with a 498-byte error fragment, and history HTTP 500 with the complete error page. The earlier response-status/body defect is resolved; the remaining finding above concerns headers.
* **Anonymous HTMX login / missing fragment:** anonymous `/login` now returns empty 204 + `HX-Redirect: /login`; history restoration returns empty 409 + the same redirect. A nonempty servlet context path correctly produces `/b2b/login`. The controller no longer selects the nonexistent `content` fragment.
* **Independent servlet registration:** the account filter is constructed in the security-chain factory rather than published as a servlet `Filter` bean.
* **Overbroad account-check bypass:** a separate eligibility-bypass matcher now skips only public static resources and the exact necessary error endpoint. The new public-login test covers rendering after an invalid authenticated principal is removed.
* **Conflicting route documentation:** the old `/**` anonymous-namespace paragraph has been replaced by the narrower method/path contract.

## Other verification and remaining coverage gaps

Additional Java 25 diagnostics against the real MVC security chain verified:

* BLOCKED status, newer security version, changed role/email, missing backing account, and a generic Spring principal reject protected access with login navigation.
* Anonymous CSRF-valid POST `/logout`, unsupported OPTIONS/PUT `/login`, multi-segment invitation/reset paths, and `/error/extra` remain protected.
* Wrong, empty, over-72-byte, and malformed-Unicode passwords use the fixed generic failure destination.
* Successful login erases both token credentials and the principal hash, rotates the pre-login session ID, and permits an authenticated home request.
* Missing logout CSRF returns localized 403 while preserving the authenticated session. A valid POST logout invalidates that session and expires `B2BSESSION` with `Max-Age=0`.

These diagnostics increase confidence in the behavior, but do not replace committed regressions. The new form suite now has seven tests; the real PostgreSQL web suite still has only two. The planned loader/access-service/provider/filter unit tests, adapter tests, and broader security route matrix remain absent. Important outstanding automated coverage includes stale sessions after block/version rotation/unblock, eligibility rejection before service execution across all transports, login-completion state races, password boundary/hash-redaction cases, custom cookie names, and exact protected/public dispatcher boundaries. Some newly added test names also promise authentication cleanup without asserting the saved session/security context explicitly.

The new cookie assertion improves the previous logout test, but it checks only that a `Set-Cookie` value contains `Max-Age=0`; it does not establish the configured cookie name/path or session invalidation. The independent diagnostic confirmed default-cookie deletion and invalidation, so this is a remaining test-strength gap rather than another reproduced implementation defect.

The full-context bootstrap ADMIN journey, real JDBC mappings, and database-backed version/role/status transitions remain unverified here. Docker access limitations do not prevent adding the missing Docker-free unit/MVC regressions or the proxy initialization regression.

## Commands and execution limits

All focused suites below were run with Java 25 by setting `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home`:

```shell
./mvnw --batch-mode --no-transfer-progress -Pmvc-template-tests test
./mvnw --batch-mode --no-transfer-progress -Punit-tests test
./mvnw --batch-mode --no-transfer-progress -Parchitecture-checks test
git diff --check
```

Results: MVC **54 passed**, unit **81 passed**, architecture **19 passed**, zero failures/errors. Tracked whitespace checks passed. These Maven runs also compiled production/test sources successfully.

Independent diagnostics were run on Java 25 using the project's actual test classpath. The previous diagnostic was reused to verify all three fixes; additional boundary/header diagnostics were created only in `/private/tmp/ph2-review02.X6pbVO/ReviewBoundaryDiagnostics.java`.

`docker info --format '{{.ServerVersion}}'` still failed with permission denied for the configured Docker Unix socket under the current sandbox. PostgreSQL, migration, and complete-suite tests were not executed in this review. No deployment or database mutation outside test fixtures was performed.

After correcting the filter order and adding its regression, rerun the focused MVC/security tests, then execute the PostgreSQL, migration, and complete suites with Java 25 and accessible Docker before final acceptance.
