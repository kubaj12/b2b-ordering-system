package io.github.kubaj12.online_store.customers.application;

import java.util.UUID;

/** Internal ordering boundary; returned billing data is never a controller/template model. */
public interface CustomerBillingSnapshotProvider {
    CustomerProfileData billingDataForOrder(UUID authenticatedCustomerId);
}
