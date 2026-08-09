# Business to business ordering system

## Project Summary

The project is a private e-commerce web application intended only for selected customers to order products. It is not a public storefront.

Customers will not make payments through the application. Their role is simply to browse the available products, place an order, and provide a delivery address. Once an order is submitted, an employee should receive a notification. The application's responsibility is limited to collecting the order and notifying the employee about it. We do not need to implement payment processing, invoicing, delivery management, or other related processes.

Employees will need to manually maintain the available quantity of each product. It sould be done by dedicated inventory management page.

The application will allow authorized customers to:

- create an account via an invitation link;
- sign in and sign out;
- browse products and product variants;
- view current prices and product availability;
- add, update, and remove items in a shopping cart;
- submit an order;
- view their own order history and order details;
- manage basic account information where applicable.

The application will allow employees to:

- create and manage customer invitations;
- activate, block, or otherwise manage customer accounts;
- create and manage products, variants, prices, and inventory;
- view and manage orders;

The application will allow authorized administrators to:

- the same privileges as employees
- activate, block, or otherwise manage employees accounts;
- access administrative views unavailable to customers and employees.

The primary interface will be a server-rendered web application.

The application may expose small internal HTTP endpoints required by HTMX or administrative operations, but the main interface is HTML rendered by Spring MVC and Thymeleaf.

This file and all other version-controlled documentation **must not contain confidential or secret values**.

The user interface should be in Polish.

## Selected Technology Stack

### Application Platform

- **Java** using a supported LTS release selected by the project configuration;
- **Spring Boot** as the application framework;
- **Spring MVC** for request handling and server-side web flows;
- **Thymeleaf** for rendering full HTML pages and reusable HTML fragments;
- **HTMX** for partial-page updates and interactive behavior without a full SPA;
- **Bootstrap** for responsive layout and reusable UI components.

### Security

- **Spring Security**;
- form-based login;
- server-side HTTP sessions;
- secure session cookies;
- role-based authorization;
- CSRF protection enabled for state-changing browser requests.

### Persistence

- **PostgreSQL** as the relational database;
- **Spring Data JPA** for repository abstractions;
- **Hibernate** as the JPA implementation used by Spring Boot;
- **Flyway** for versioned database schema migrations.

### Testing and Local Infrastructure

- **JUnit 5** for automated tests;
- **Spring Boot Test** and **MockMvc** for Spring and MVC integration tests;
- **Testcontainers** with PostgreSQL for database integration tests;
- **Playwright** for a small set of critical end-to-end browser tests;
- **Docker Compose** for local supporting services such as PostgreSQL.

## Architectural Style

The application will be implemented as a **modular monolith**.

## Access and Account Model

The application is invitation-only.

A account activation flow is:

1. An administrator creates an invitation for a specific email address. The administrator must also provide information about the customer.
2. The customer receives an activation link.
3. The customer opens the link and sets a password.
4. The account becomes active.
5. The invitation is invalidated and cannot be used again.

All application pages must require authentication except for explicitly permitted endpoints.

### Initial Roles

- **CUSTOMER** — may access the catalog, cart, their own profile, and their own orders;
- **EMPLOYEE** — may manage invitations, accounts, catalog data, inventory, and orders.
- **ADMIN** — has the same privileges as an EMPLOYEE and may also manage EMPLOYEE accounts.

### Account Status

The account model should support these statuses:

```text
INVITED
ACTIVE
BLOCKED
EXPIRED
```

## Core Domain Model

## User

Represents a user account in the system.

Each user has exactly one role determining the type of account and its access level.

Data:

- `id`;
- email;
- password hash;
- account status;
- role;
- display name;
- last successful login timestamp;
- account-related preferences;
- creation and update timestamps.

Each user has exactly one of the following roles:

CUSTOMER — a customer using the store;
EMPLOYEE — an employee responsible for store operations;
ADMIN — an administrator with all employee permissions and additional administrative privileges.

## 10.2 CustomerProfile

Represents customer-specific business information.

A CustomerProfile is associated with a User whose role is CUSTOMER.

Data:

- `id`;
- reference to User;
- display name or company name;
- optional phone number;
- billing information;
- creation and update timestamps.

Billing information may include:

- individual or company name;
- billing address;
- tax identification number, if applicable;
- optional company details.

## 10.3 Role

Represents the type and authorization level of a user account.

Roles are:

- CUSTOMER;
- EMPLOYEE;
- ADMIN.

Each User has exactly one role.

ADMIN is considered a privileged employee role. An administrator has access to all functionality available to EMPLOYEE, in addition to administrator-only functionality.

### 10.4 Product

Represents a product offered in the catalog.

Data:

- `id`;
- product name;
- description;
- active/visible flag;
- optional category or classification;
- creation and update timestamps.

### Sku

Data:
- `id`;
- quantity;

### Attribute

Data:

- `id`;
- name;
- price;

### Sku attributes

Table that list each attribute combination that is allowed for each product.

Data:
- sku_id;
- prod_id;
- attr_id;
- attr_val;

### 10.8 Cart

Data:

- `id`;
- owning customer;
- cart status;
- creation and update timestamps.

### 10.9 CartItem

Data:

- `id`;
- parent cart;
- SKU id;
- quantity;
- creation and update timestamps.

### 10.10 Order

Data:

- `id`;
- public order number distinct from the database identifier;
- owning customer;
- order status;
- total amount;
- billing snapshot;
- delivery snapshot;
- submitted timestamp;
- creation and update timestamps.

### 10.11 OrderItem

Represents an item recorded as part of an order.

Data:

- `id`;
- order id;
- SKU snapshot;
- product name snapshot;
- Sku attributes snapshot;
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
- invitation status;
- administrator who created the invitation, when auditing is required;
- creation timestamp.