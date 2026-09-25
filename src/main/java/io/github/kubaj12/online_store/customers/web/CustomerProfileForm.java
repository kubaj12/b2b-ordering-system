package io.github.kubaj12.online_store.customers.web;

import jakarta.validation.constraints.*;
import io.github.kubaj12.online_store.customers.application.CustomerProfileData;
import io.github.kubaj12.online_store.shared.web.validation.*;

@CompletePolishAddress
public record CustomerProfileForm(
    @NotBlank @Size(max=255) String companyName,
    @NotBlank @ValidPolishNip String nip,
    @NotBlank @Size(max=255) String street,
    @NotBlank @Size(max=32) String buildingNumber,
    @Size(max=32) String unitNumber,
    @NotBlank @ValidPolishPostalCode String postalCode,
    @NotBlank @Size(max=120) String city,
    @NotBlank String country,
    @ValidPolishPhone String phone
) implements PolishAddressFields {
    public CustomerProfileForm() { this("", "", "", "", "", "", "", "PL", ""); }
    CustomerProfileData toData() { return CustomerProfileData.of(companyName, nip, street, buildingNumber,
            unitNumber, postalCode, city, country, phone); }
    static CustomerProfileForm from(CustomerProfileData p) {
        var a = p.billingAddress();
        return new CustomerProfileForm(p.companyName(), p.nip().value(), a.street(), a.buildingNumber(),
                a.unitNumber(), a.postalCode().value(), a.city(), a.country(),
                p.phone() == null ? "" : p.phone().value());
    }
}
