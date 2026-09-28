# PH3-TASK-07 Implementation

## Scope

Enforced staff-only access to customer profiles and customer administration forms so authenticated customers cannot access records by guessing or changing URL identifiers or submitting requests directly.

## Changes

- Added an HTTP request authorization rule for `/staff/customers` and every nested path in `BrowserSecurityConfiguration`.
- Only `EMPLOYEE` and `ADMIN` accounts may reach those endpoints. The request filter chain rejects other roles before controller argument binding and application-service lookups.
- Kept the controller-level `@PreAuthorize("hasAnyRole('EMPLOYEE','ADMIN')")` guard as a second authorization boundary. It covers directory, create, detail, edit, block, and unblock actions, including full-page and HTMX requests.
- The staff customer detail and edit routes require UUID path values, but authorization is independent of the supplied ID. A customer therefore cannot access their own profile, another profile, or the administrative forms by changing a path or submitting a crafted request.

## Verification

Repository inspection confirmed all customer-profile reads and writes exposed over HTTP are routed through the staff-only `CustomerAdministrationController`. The request-level rule now denies customers before those controller methods can call `CustomerAdministrationService`.

No automated tests were added or run for this task.

## Review follow-up

Added `CustomerAdministrationAuthorizationTests` after review identified missing route-level coverage. The tests cover customer access to the directory, create form, own/other detail and edit URLs, crafted create/edit/block/unblock requests, and an HTMX request. They assert forbidden responses and no customer-administration service calls, and confirm employee/admin access to staff pages. Verification: `./mvnw -q -Dtest=CustomerAdministrationAuthorizationTests test` passed.
