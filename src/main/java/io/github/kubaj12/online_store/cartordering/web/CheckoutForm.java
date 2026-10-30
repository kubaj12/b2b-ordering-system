package io.github.kubaj12.online_store.cartordering.web;

import io.github.kubaj12.online_store.cartordering.application.CheckoutDetails;
import io.github.kubaj12.online_store.shared.validation.PolishAddress;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.github.kubaj12.online_store.shared.validation.PolishPhoneNumber;
import io.github.kubaj12.online_store.shared.web.validation.ValidPolishPhone;

/** Customer supplied, per-order checkout details. Billing profile fields are deliberately absent. */
public class CheckoutForm {
    @Size(max = 100) @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*", message = "Pole nie może zawierać znaków sterujących.")
    private String purchaseOrderNumber;
    @NotBlank @Size(max = 160) @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*", message = "Pole nie może zawierać znaków sterujących.")
    private String contactName;
    @NotBlank @Size(max = 12) @ValidPolishPhone
    private String contactPhone;
    @NotBlank @Size(max = 255) @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*", message = "Pole nie może zawierać znaków sterujących.")
    private String street;
    @NotBlank @Size(max = 32) @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*", message = "Pole nie może zawierać znaków sterujących.")
    private String buildingNumber;
    @Size(max = 32) @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*", message = "Pole nie może zawierać znaków sterujących.")
    private String unitNumber;
    @NotBlank @Pattern(regexp = "[0-9]{2}-[0-9]{3}")
    private String postalCode;
    @NotBlank @Size(max = 120) @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*", message = "Pole nie może zawierać znaków sterujących.")
    private String city;
    private String checkoutToken;

    public String getPurchaseOrderNumber() { return purchaseOrderNumber; }
    public void setPurchaseOrderNumber(String value) { purchaseOrderNumber = value == null ? null : value.strip(); }
    public String getContactName() { return contactName; }
    public void setContactName(String value) { contactName = value == null ? null : value.strip(); }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String value) { contactPhone = value == null ? null : PolishPhoneNumber.normalize(value); }
    public String getStreet() { return street; }
    public void setStreet(String value) { street = value == null ? null : value.strip(); }
    public String getBuildingNumber() { return buildingNumber; }
    public void setBuildingNumber(String value) { buildingNumber = value == null ? null : value.strip(); }
    public String getUnitNumber() { return unitNumber; }
    public void setUnitNumber(String value) { unitNumber = value == null ? null : value.strip(); }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String value) { postalCode = value == null ? null : value.strip(); }
    public String getCity() { return city; }
    public void setCity(String value) { city = value == null ? null : value.strip(); }
    public String getCountry() { return "PL"; }
    public void setCountry(String ignored) { }
    public String getCheckoutToken() { return checkoutToken; }
    public void setCheckoutToken(String value) { checkoutToken = value; }

    CheckoutDetails toDetails() {
        return new CheckoutDetails(purchaseOrderNumber, contactName, PolishPhoneNumber.of(contactPhone),
                PolishAddress.of(street, buildingNumber, unitNumber, postalCode, city, "PL"));
    }

    /** Only bounded, validated field values enter the session draft; errors remain on the current response. */
    CheckoutForm safeCopy(org.springframework.validation.BindingResult errors) {
        var copy = new CheckoutForm();
        if (safe(errors, "purchaseOrderNumber")) copy.setPurchaseOrderNumber(purchaseOrderNumber);
        if (safe(errors, "contactName")) copy.setContactName(contactName);
        if (safe(errors, "contactPhone")) copy.setContactPhone(contactPhone);
        if (safe(errors, "street")) copy.setStreet(street);
        if (safe(errors, "buildingNumber")) copy.setBuildingNumber(buildingNumber);
        if (safe(errors, "unitNumber")) copy.setUnitNumber(unitNumber);
        if (safe(errors, "postalCode")) copy.setPostalCode(postalCode);
        if (safe(errors, "city")) copy.setCity(city);
        copy.setCheckoutToken(checkoutToken);
        return copy;
    }

    private static boolean safe(org.springframework.validation.BindingResult errors, String field) {
        return errors == null || !errors.hasFieldErrors(field);
    }
}
