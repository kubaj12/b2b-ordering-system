package io.github.kubaj12.online_store.cartordering.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.github.kubaj12.online_store.shared.validation.PolishPhoneNumber;
import io.github.kubaj12.online_store.shared.web.validation.ValidPolishPhone;

/** Customer supplied, per-order checkout details. Billing profile fields are deliberately absent. */
public class CheckoutForm {
    @Size(max = 100)
    private String purchaseOrderNumber;
    @NotBlank @Size(max = 160)
    private String contactName;
    @NotBlank @ValidPolishPhone
    private String contactPhone;
    @NotBlank @Size(max = 255)
    private String street;
    @NotBlank @Size(max = 32)
    private String buildingNumber;
    @Size(max = 32)
    private String unitNumber;
    @NotBlank @Pattern(regexp = "[0-9]{2}-[0-9]{3}")
    private String postalCode;
    @NotBlank @Size(max = 120)
    private String city;
    private String country = "PL";
    private String checkoutToken;

    public String getPurchaseOrderNumber() { return purchaseOrderNumber; }
    public void setPurchaseOrderNumber(String value) { purchaseOrderNumber = value; }
    public String getContactName() { return contactName; }
    public void setContactName(String value) { contactName = value; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String value) { contactPhone = value == null ? null : PolishPhoneNumber.normalize(value); }
    public String getStreet() { return street; }
    public void setStreet(String value) { street = value; }
    public String getBuildingNumber() { return buildingNumber; }
    public void setBuildingNumber(String value) { buildingNumber = value; }
    public String getUnitNumber() { return unitNumber; }
    public void setUnitNumber(String value) { unitNumber = value; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String value) { postalCode = value; }
    public String getCity() { return city; }
    public void setCity(String value) { city = value; }
    public String getCountry() { return "PL"; }
    public void setCountry(String ignored) { country = "PL"; }
    public String getCheckoutToken() { return checkoutToken; }
    public void setCheckoutToken(String value) { checkoutToken = value; }
}
