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

HTMX is loaded by the shell and the shared script restores focus to validation summaries after a
swap. Request detection, full-page versus fragment selection, CSRF-header propagation, response
retargeting, and history/cache semantics belong to the next Phase 1 web-handling task.
