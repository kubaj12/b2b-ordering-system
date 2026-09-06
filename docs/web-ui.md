# Shared web UI conventions

The application renders Polish server-side HTML with Thymeleaf. Feature modules own their
controllers, form objects, and view models; the resources under `templates/layout` and
`templates/fragments` provide the shared rendering contract.

## Page layout

Pages compose the application shell with the native Thymeleaf layout fragment:

```html
<html lang="pl" xmlns:th="http://www.thymeleaf.org"
      th:replace="~{layout/application :: layout(#{page.title}, 'navigation-key', ~{::#page-content})}">
<body>
<section id="page-content">
    <!-- module-owned page content -->
</section>
</body>
</html>
```

The shell supplies the Polish document language, responsive metadata, local Bootstrap and HTMX
assets, a skip link, role-aware navigation, the flash-message region, the main landmark, and the
footer. `activeNavigation` is a presentation hint only and never replaces server authorization.

## Form errors

Call both form fragments inside the element carrying `th:object` so the Thymeleaf `#fields`
expression object has the correct binding context:

```html
<form th:object="${customerForm}">
    <div th:replace="~{fragments/forms :: errorSummary}"></div>

    <label for="companyName">Nazwa firmy</label>
    <input id="companyName" th:field="*{companyName}"
           th:classappend="${#fields.hasErrors('companyName')} ? ' is-invalid' : ''"
           th:attr="aria-invalid=${#fields.hasErrors('companyName')} ? 'true' : null,
                    aria-describedby=${#fields.hasErrors('companyName')} ? 'companyName-errors' : null">
    <div th:replace="~{fragments/forms :: fieldErrors('companyName')}"></div>
</form>
```

Routine user-correctable validation must return the same form view with its `BindingResult`.
The generic HTTP 400 view is reserved for a request that cannot be safely returned to a form.

## Flash messages

The layout recognizes four optional, already-localized flash attributes:

- `flashSuccessMessage` and `flashInfoMessage` use polite status announcements;
- `flashWarningMessage` and `flashErrorMessage` use assertive alert announcements.

Controllers should resolve the Polish text through `MessageSource` before adding it to
`RedirectAttributes`. Thymeleaf escapes every value. Flash alerts are deliberately not dismissed
automatically, so keyboard focus is not discarded.

## Pagination

The pagination fragment accepts a Spring `Page`, its context-relative path, and a map of retained
query values:

```html
<div th:replace="~{fragments/pagination :: pagination(${resultPage}, '/staff/inventory', ${retainedParameters})}"></div>
```

Controllers must construct `retainedParameters` from an explicit allowlist of their filter fields;
do not pass the request parameter map through unchanged. Values are lists so repeated parameters
remain representable. Exclude `page` and `size`, which the component supplies itself. Page numbers
are zero-based in requests and one-based in visible Polish labels. The fragment clamps its rendering
window defensively, so an out-of-range page cannot create an unbounded Thymeleaf sequence. A
controller must still reject the value or redirect to the canonical last page before executing its
database query.

## Browser errors

Module web adapters may raise `WebErrorException` for an expected validation, forbidden,
not-found, or conflict outcome after translating the module-owned application result. Message
arguments are rendered to the browser and must be safe for the authenticated user. Do not map raw
database exceptions globally and do not put credentials, tokens, customer-confidential fields, or
other secrets in error arguments.

Unexpected runtime errors and framework `ErrorResponse` failures with a 5xx status render the
generic unexpected-error page with a generated reference. The matching log entry contains the
status, exception and cause types, and bounded stack-frame locations, but omits exception messages.
Exception details, request paths, binding data, and stack traces are excluded from browser error
attributes.

## HTMX boundary

HTMX is a progressive enhancement of the authenticated server-rendered application, not an API.
Each operation has one MVC route and one controller method. That method performs binding,
validation, authorization, and exactly one application-service interaction before choosing its
HTML representation. Do not create a second route selected by an `HX-Request` mapping condition,
a `/fragment` route, a REST controller, or a JSON DTO for the HTMX path.

Accept `HtmxRequest` as a controller argument and use `BrowserResponse` only at the final rendering
step:

```java
@GetMapping(produces = MediaType.TEXT_HTML_VALUE)
@PreAuthorize("hasAnyRole('EMPLOYEE', 'ADMIN')")
ModelAndView inventory(HtmxRequest request, HttpServletResponse response) {
    InventoryView view = inventoryQueries.loadForCurrentStaff();
    return BrowserResponse.render(
            request,
            response,
            "inventory/index",
            "inventory/index :: inventoryContent",
            Map.of("inventory", view)
    );
}
```

The full template and its named content fragment are one Thymeleaf source. A regular request
renders the application layout; an HTMX request renders only the named fragment. An
`HX-History-Restore-Request` always receives the complete page. Responses that vary this way carry
`Vary: HX-Request, HX-History-Restore-Request`.

The `HX-Request` header controls rendering only. It must never select a role, principal, customer,
price list, object owner, validation policy, or application-service method. User/customer scope is
derived from the authenticated principal and enforced by the application service.

### Commands, validation, and redirects

Use the same form object, Jakarta validation constraints, `BindingResult`, role annotation, and
application command for both request modes. On routine binding errors, do not call the application
service: return the same form page/fragment with its `BindingResult` and HTTP 400. Translate
application validation outcomes back to safe field/global errors where correction is possible.
Use `WebErrorException` only when the request cannot safely return to its form.

After a successful command that needs full navigation, call `BrowserResponse.redirect`. It applies
Post/Redirect/Get with HTTP 303 to an ordinary form and `HX-Redirect` to an HTMX form. Inline
commands may instead return an updated fragment. Mutating responses must not push browser history.

### CSRF

Every ordinary form keeps a Thymeleaf `th:action`, which lets Spring Security add its hidden CSRF
field. The layout also exposes the request's masked CSRF token and header name as metadata. The
shared script copies them to unsafe HTMX requests through `htmx:configRequest`. This is only token
transport: Spring Security remains authoritative and no HTMX request is excluded from CSRF
protection.

If authentication is missing or a session has expired, a regular request is redirected to
`/login`. Any HTMX request instead receives an empty 401 response with `HX-Redirect: /login`,
forcing a complete browser navigation so a login document is never swapped into a fragment. The
same entry point handles an anonymous unsafe request when CSRF rejects it before authorization.
Authenticated authorization and CSRF failures remain localized 403 responses.

HTMX's separate history-restoration loader does not process response redirect headers. Its request
therefore receives a non-swappable error status, and the shared script handles
`htmx:historyCacheMissLoadError` by validating the same-origin `HX-Redirect` target and performing a
full browser navigation. Never return a 2xx or 3xx empty redirect response to an
`HX-History-Restore-Request`: HTMX would swap that empty body into the history element.

The anonymous route boundary is intentionally narrow: `/login`, `/invitations/accept[/**]`,
`/password-reset[/**]`, `/error[/**]`, and Spring Boot's common static-resource locations. These
routes bypass authentication only; CSRF protection still applies to their unsafe methods. All
other routes are authenticated by default.

### Errors and focus

Full-page and HTMX responses keep the same status and localized safe message: 400 for invalid or
malformed input, 403 for denied access or CSRF failure, 404 for a missing resource, 409 for a stale
or conflicting write, and a sanitized 5xx response with a diagnostic reference for an unexpected
failure. HTMX errors contain only the named error fragment and retarget `#main-content` when the
current operation cannot render its own form.

HTMX does not swap 4xx/5xx responses by default. The server therefore marks only application-
rendered safe HTML errors, and the shared script enables swapping only for that marker. It then
focuses a validation summary or terminal error after the swap.

### History and private data

The layout disables HTMX DOM history snapshots so authenticated page contents are not copied to
session storage. History entries may still be used for idempotent GET navigation; on a cache miss,
the history-restore request receives a fresh complete page from the server. Spring Security's
no-store response headers remain the HTTP-cache boundary for authenticated content.
