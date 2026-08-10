# B2B Ordering System

## Purpose and Scope

The project is a private, invitation-only web application through which selected business customers place product orders. It is not a public storefront.

Each customer is a company represented by exactly one user account. Customers use the application to browse the catalog, see their negotiated prices and current stock, maintain a cart, provide a delivery address, submit orders, and view their order history.

After an order is submitted, the application records it, reduces stock, displays a confirmation to the customer, and notifies employees. Employees then handle invoicing, fulfillment, and delivery outside the application.

The following are outside the application's scope:

- payments and credit management;
- invoice generation, accounting, and KSeF integration;
- warehouse or ERP integration;
- fulfillment, shipment booking, and delivery tracking;
- returns and complaints;
- CSV import and export.

Inventory is maintained manually by employees through a dedicated inventory page.

## Roles and Permissions

Each user has exactly one role:

- **CUSTOMER** — may browse the catalog, maintain their cart, submit orders, view their company profile as read-only, change their password, and view only their own orders;
- **EMPLOYEE** — may manage customer invitations and customer accounts, catalog data, price lists, customer-specific prices, inventory, and all orders;
- **ADMIN** — has all EMPLOYEE permissions and may additionally create, block, unblock, and manage employee accounts.

Authorization must be enforced on the server. In particular, a customer must never be able to access another customer's profile, cart, prices, or orders by changing a URL or request parameter.

## Accounts and Invitations

The application is invitation-only. Employees and administrators may invite customers. Administrators may also create employee accounts.

The first administrator is created through an initial database seed. Its credentials must not be stored in version-controlled files.

### Customer activation flow

1. An employee or administrator creates an invitation for a unique email address and provides the customer's mandatory company and billing data.
2. The application sends a single-use activation link to that address.
3. The customer opens the link and sets a password.
4. The application creates and activates the customer user account and customer profile.
5. The invitation becomes accepted and cannot be used again.

Activation occurs automatically after a valid password is created. Resending an invitation revokes the previous pending invitation and creates a new token.

A pending customer invitation retains the company and billing data supplied by the employee. Acceptance copies that data into the new `CustomerProfile`.

Employee accounts use the same email activation and password-creation mechanism but do not have a `CustomerProfile`. Only an administrator may issue an employee invitation.

### User account statuses

- `ACTIVE` — the user may sign in and use the features allowed by their role;
- `BLOCKED` — the user may not sign in, and existing sessions must be invalidated.

Employees may block and unblock customer accounts. Administrators may block and unblock employee accounts. Blocking an account does not remove its historical orders.

A user account contains an internal identifier, unique normalized email address, password hash, role, account status, last successful login timestamp, and creation and update timestamps.

### Invitation statuses

- `PENDING` — the invitation may be accepted before its expiration time;
- `ACCEPTED` — the invitation has been used successfully;
- `EXPIRED` — its expiration time has passed;
- `REVOKED` — it was invalidated before use.

Invitation and password-reset tokens must be random, stored only as secure hashes, and be single-use. Invitations expire after seven days, and password-reset links expire after one hour. Users must be able to reset a forgotten password through email. Customers may otherwise change only their password; employees maintain their customer profile data.

All pages require authentication except login, invitation acceptance, password reset, static resources, and necessary error pages.

## Customer and Billing Data

A `CustomerProfile` represents one company and has a one-to-one relationship with a CUSTOMER user.

Employees and administrators create and edit customer profiles. Customers may view their own profile but may not edit it.

The profile must contain the purchaser-identification data needed by an employee to issue a standard Polish B2B invoice outside the application:

- legal company name;
- Polish tax identification number (`NIP`);
- billing address consisting of street, building number, optional unit number, postal code, city, and country;
- optional contact phone number;
- creation and update timestamps.

The company name, NIP, and complete billing address are mandatory. Billing addresses are limited to Poland. NIP must be normalized, validated, and unique between customer profiles. These fields identify the invoice purchaser; seller data, invoice numbering, VAT calculation, invoice issuance, and KSeF submission remain outside the application.

The billing data effective at order submission must be copied into the order as an immutable snapshot so later profile changes do not alter historical orders.

## Catalog

### Product

A product groups one or more sellable SKUs.

Data:

- `id`;
- name;
- description;
- category;
- active status;
- creation and update timestamps.

An active product remains visible even when every SKU is out of stock. An inactive product is hidden from customers but remains available in historical order data.

### SKU

A SKU is a specific sellable product variant.

Data:

- `id`;
- product reference;
- unique SKU code;
- current base net price in PLN;
- current available quantity;
- active status;
- zero or more attribute values describing the variant;
- creation and update timestamps.

Quantity belongs only to the SKU. `available quantity` is the single inventory quantity field; there is no separate product quantity or second stock field. It is a non-negative integer.

An inactive SKU cannot be added to a cart or ordered. Existing order history referring to it remains unchanged.

### Variant attributes

Variant attributes describe differences such as size or color. An attribute definition has a name, and an attribute value belongs to a definition. A SKU may reference at most one value from each attribute definition. Prices are not attached to attributes.

### Catalog browsing

Customers must be able to:

- search by product name or SKU code;
- filter by category;
- filter by availability, including low-stock and out-of-stock SKUs;
- view product descriptions, variants, their effective prices, and exact current quantities;
- view active out-of-stock products and SKUs, clearly marked as unavailable.

Low stock means an available quantity from one through five units. Out-of-stock and inactive SKUs cannot be added to a cart. Catalog results must be paginated.

## Pricing

All prices are net prices in PLN. VAT and gross invoice totals are calculated outside the application. Monetary values use decimal arithmetic with two fractional digits and `HALF_UP` rounding.

Every active SKU has one current base price. Employees and administrators may also manage:

- named price lists containing one current price per selected SKU;
- assignment of at most one price list to a customer;
- customer-specific prices for individual customer and SKU pairs.

The effective customer price is resolved in this order:

1. a customer-specific SKU price;
2. the price from the customer's assigned price list;
3. the SKU base price.

Customers see only their effective prices. Price changes affect carts and future orders but never alter submitted orders. Checkout must revalidate effective prices. If a price changed after the checkout review was generated, the order must not be submitted until the customer reviews the updated total.

## Inventory Management

Employees and administrators maintain SKU quantities on a dedicated inventory page. The page must support:

- searching by product name or SKU code;
- filtering by category;
- filtering out-of-stock SKUs;
- filtering low-stock SKUs, meaning an available quantity from one through five units;
- inline quantity updates with non-negative integer validation;
- displaying who last changed a quantity and when.

Inventory results must be paginated. Each inventory change must record the SKU, previous quantity, new quantity, employee, and timestamp. Concurrent edits must not silently overwrite a newer quantity.

Adding a SKU to a cart does not reserve stock. Stock is checked again when the order is submitted.

Order submission and stock reduction must occur in one database transaction. When concurrent submissions request more units than remain available, the first transaction that successfully secures the stock succeeds. The other submission is rejected before an order is created, and the customer is returned to checkout with the current quantity. Stock must never become negative.

Submitting an order reduces each SKU's available quantity by the ordered amount. Marking an order as cancelled does not restore stock automatically. If stock needs to be restored after an external cancellation, an employee includes that correction in the next manual inventory update.

## Shopping Cart

Each customer has at most one `ACTIVE` cart, persisted in the database and available across login sessions.

Cart statuses are:

- `ACTIVE` — may be edited and submitted;
- `COMPLETED` — was successfully converted into an order and is no longer editable.

A new active cart is created when needed after the previous cart is completed.

A cart item contains a SKU and a positive integer quantity. Adding the same SKU again updates the existing line rather than creating a duplicate. A cart may contain no more than 500 distinct lines. Requested quantities remain subject to the current SKU availability at checkout.

Current effective prices and stock must be revalidated when checkout is displayed and again when the order is submitted. Invalid, inactive, repriced, or unavailable items must be clearly presented to the customer for correction.

## Checkout and Delivery Address

Checkout contains:

- a review of cart lines, effective net prices, and the net order total in PLN;
- the customer's read-only company and billing data;
- a delivery address form;
- no purchase-order number, reference number, or order comment fields.

Delivery is limited to addresses in Poland. The customer enters a delivery address for every order; the application does not maintain an address book.

The delivery address contains:

- recipient company or organization name;
- contact person's full name;
- contact phone number;
- street;
- building number;
- optional unit number;
- postal code;
- city;
- country, fixed to Poland.

All fields except unit number are mandatory. The submitted delivery address is stored as an immutable order snapshot.

Submission must use a one-time checkout token. Reusing a consumed token must not create another order.

## Orders

Submitting a valid checkout must complete the following steps in one database transaction:

1. verify the one-time checkout token;
2. revalidate the customer account, cart, SKUs, prices, and available quantities;
3. create the order and immutable order items;
4. reduce SKU quantities;
5. mark the cart as `COMPLETED`;
6. consume the checkout token;
7. make the order available to the internal employee inbox.

After the transaction commits, the application must display a confirmation page containing the public order number and immediately trigger customer and employee email notifications.

Submitted order contents are immutable. Customers cannot modify or cancel orders in the application.

### Order statuses

- `SUBMITTED` — the order was accepted by the application and awaits employee review;
- `ACKNOWLEDGED` — an employee has acknowledged the order;
- `CLOSED` — the intake process is complete and further handling continues outside the application;
- `CANCELLED` — an employee recorded that the order was cancelled outside the application.

Employees and administrators may transition `SUBMITTED` orders to `ACKNOWLEDGED` or `CANCELLED`, and `ACKNOWLEDGED` orders to `CLOSED` or `CANCELLED`. Customers may view statuses but may not change them. These statuses represent only order intake and do not track payment, invoicing, fulfillment, or delivery.

### Order data

An order contains:

- internal database identifier;
- unique public order number in the format `ORD-YYYY-NNNNNN`;
- customer reference;
- status;
- total net amount and currency (`PLN`);
- customer company and billing snapshot;
- delivery address snapshot;
- submitted timestamp;
- creation and update timestamps.

An order item contains immutable snapshots of:

- SKU code;
- product name;
- variant attributes;
- effective unit net price and currency;
- quantity;
- line net total.

The order total is the sum of line net totals. Historical order data must not change when current products, SKUs, prices, customer data, or addresses change.

## Order Inbox and Notifications

All active EMPLOYEE and ADMIN accounts can view all orders in a shared internal order inbox. The inbox must support filtering by order status and opening complete order details.

Immediately after an order transaction commits, the application must:

- send a confirmation email to the customer;
- send an order notification email to every active EMPLOYEE and ADMIN account;
- expose the order in the internal inbox.

Order emails must contain only the public order number, a concise non-sensitive summary, and a link to the authenticated application. Billing and delivery details must be viewed only after authentication.

Email delivery failure must not roll back or remove an accepted order. Email notifications must be recorded and retried after temporary failures, and a permanent failure must be visible to an employee or administrator. The internal order inbox is the authoritative record even if email delivery fails.

## Security

The application uses:

- Spring Security with form-based login and server-side sessions;
- BCrypt password hashing and a minimum password length of 12 characters;
- secure, `HttpOnly`, and `SameSite=Lax` session cookies;
- CSRF protection for all state-changing browser requests, including HTMX requests;
- role-based and object-level authorization;
- login throttling;
- invalidation of active sessions when an account is blocked or its password is reset.

Passwords, raw invitation or reset tokens, email credentials, and other secrets must never be stored in version-controlled documentation or configuration.

Security-relevant changes must record the acting user and timestamp, including invitations, account blocking, price changes, inventory changes, and order status changes.

## User Interface

The user interface must be in Polish and use Polish formatting for dates and PLN monetary values. It must be responsive and usable by keyboard on current desktop and mobile browsers.

The primary interface is server-rendered HTML. HTMX may provide partial-page updates, but HTMX endpoints follow the same authentication, authorization, validation, and CSRF rules as full-page requests. The application does not expose a public external API.

## Architecture and Technology

The application is a modular monolith with modules for:

- identity and access;
- customers;
- catalog and pricing;
- inventory;
- cart and ordering;
- notifications.

The selected stack is:

- Java using a supported LTS release pinned by the build configuration;
- Spring Boot;
- Spring MVC;
- Thymeleaf;
- HTMX;
- Bootstrap;
- Spring Security;
- PostgreSQL;
- Spring Data JPA with Hibernate;
- Flyway for versioned database schema migrations.

All timestamps are stored in UTC and displayed in the Europe/Warsaw time zone.

## Expected Scale

The application is designed for:

- no more than 100 customer companies;
- no more than 1,000 products;
- no more than 15 SKUs per product, or approximately 15,000 SKUs in total;
- no more than 50 orders per day;
- no more than 50 concurrent users;
- no more than 500 distinct lines in a cart or order;
- manual inventory updates typically performed once per day.
