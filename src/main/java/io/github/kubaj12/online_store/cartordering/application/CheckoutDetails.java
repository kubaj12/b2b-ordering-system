package io.github.kubaj12.online_store.cartordering.application;

import io.github.kubaj12.online_store.shared.validation.PolishAddress;
import io.github.kubaj12.online_store.shared.validation.PolishPhoneNumber;

/** Validated customer input for a single checkout, independent of browser binding. */
public record CheckoutDetails(String purchaseOrderNumber, String contactName,
        PolishPhoneNumber contactPhone, PolishAddress deliveryAddress) {
    public CheckoutDetails {
        purchaseOrderNumber = text(purchaseOrderNumber, 100, false);
        contactName = text(contactName, 160, true);
        if (contactPhone == null || deliveryAddress == null) {
            throw new IllegalArgumentException("Delivery phone and address are required");
        }
        text(deliveryAddress.street(), 255, true);
        text(deliveryAddress.buildingNumber(), 32, true);
        text(deliveryAddress.unitNumber(), 32, false);
        text(deliveryAddress.city(), 120, true);
    }

    private static String text(String input, int limit, boolean required) {
        String value = input == null ? "" : input.strip();
        if ((required && value.isBlank()) || value.length() > limit
                || value.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                        || Character.getType(cp) == Character.FORMAT)) {
            throw new IllegalArgumentException("Invalid checkout text");
        }
        return value.isEmpty() ? null : value;
    }
}
