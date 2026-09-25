package io.github.kubaj12.online_store.customers.application;

/** Explicit customer-facing projection: deliberately contains no identifiers, company, or billing data. */
public record CustomerSelfView(String email) { }
