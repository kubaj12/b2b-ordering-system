package io.github.kubaj12.online_store.customers.application;

import io.github.kubaj12.online_store.shared.validation.PolishAddress;
import io.github.kubaj12.online_store.shared.validation.PolishNip;
import io.github.kubaj12.online_store.shared.validation.PolishPhoneNumber;

/** Internal purchaser data. This type must never be used as a customer web model. */
public record CustomerProfileData(String companyName, PolishNip nip, PolishAddress billingAddress,
        PolishPhoneNumber phone) {
    public CustomerProfileData {
        companyName = companyName == null ? "" : companyName.strip();
        if (companyName.isEmpty() || companyName.length() > 255 || nip == null || billingAddress == null)
            throw new IllegalArgumentException("complete customer profile required");
    }
    public static CustomerProfileData of(String companyName, String nip, String street, String buildingNumber,
            String unitNumber, String postalCode, String city, String country, String phone) {
        return new CustomerProfileData(companyName, PolishNip.of(nip),
                PolishAddress.of(street, buildingNumber, unitNumber, postalCode, city, country),
                phone == null || phone.isBlank() ? null : PolishPhoneNumber.of(phone));
    }
}
