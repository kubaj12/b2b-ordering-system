package io.github.kubaj12.online_store.cartordering.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanWrapperImpl;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutFormValidationTests {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void requiresARealPolishPhoneAndNormalizesAcceptedNumbers() {
        var valid = form("+48 600 700 800");
        valid.setPurchaseOrderNumber("");
        valid.setCountry("DE");
        assertThat(validator.validate(valid)).isEmpty();
        assertThat(valid.getContactPhone()).isEqualTo("+48600700800");
        assertThat(valid.getCountry()).isEqualTo("PL");

        var malformed = form("1        ");
        assertThat(validator.validate(malformed)).anyMatch(error -> error.getPropertyPath().toString().equals("contactPhone"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"contactName", "contactPhone", "street", "buildingNumber", "postalCode", "city"})
    void rejectsBlankMandatoryFields(String field) {
        var form = form("600700800");
        new BeanWrapperImpl(form).setPropertyValue(field, "   ");
        assertThat(validator.validate(form)).anyMatch(error -> error.getPropertyPath().toString().equals(field));
    }

    @ParameterizedTest
    @CsvSource({"purchaseOrderNumber,100", "contactName,160", "street,255", "buildingNumber,32", "unitNumber,32", "city,120"})
    void enforcesMaximumLengthsAndRejectsControlCharacters(String field, int maximum) {
        var form = form("600700800");
        var binding = new BeanWrapperImpl(form);
        binding.setPropertyValue(field, "a".repeat(maximum));
        assertThat(validator.validate(form)).isEmpty();
        binding.setPropertyValue(field, "a".repeat(maximum + 1));
        assertThat(validator.validate(form)).anyMatch(error -> error.getPropertyPath().toString().equals(field));
        binding.setPropertyValue(field, "a\nb");
        assertThat(validator.validate(form)).anyMatch(error -> error.getPropertyPath().toString().equals(field));
        binding.setPropertyValue(field, "a\u202Eb");
        assertThat(validator.validate(form)).anyMatch(error -> error.getPropertyPath().toString().equals(field));
    }

    @ParameterizedTest
    @ValueSource(strings = {"00001", "0-001", "AA-123", "00-1234"})
    void rejectsMalformedPostalCodes(String postalCode) {
        var form = form("600700800");
        form.setPostalCode(postalCode);
        assertThat(validator.validate(form)).anyMatch(error -> error.getPropertyPath().toString().equals("postalCode"));
    }

    @Test void stripsOuterWhitespaceAndAllowsEmptyOptionalValuesAtTheServiceBoundary() {
        var form = form("0048 (600) 700-800");
        form.setPurchaseOrderNumber("  ");
        form.setUnitNumber(" ");
        form.setStreet(" Prosta ");
        form.setPostalCode(" 00-001 ");
        assertThat(validator.validate(form)).isEmpty();
        var details = form.toDetails();
        assertThat(details.purchaseOrderNumber()).isNull();
        assertThat(details.deliveryAddress().unitNumber()).isNull();
        assertThat(details.deliveryAddress().street()).isEqualTo("Prosta");
        assertThat(details.contactPhone().value()).isEqualTo("+48600700800");
    }

    @Test void validatedDetailsRejectInputFromNonBrowserCallersToo() {
        var good = form("600700800").toDetails();
        assertThatThrownBy(() -> new io.github.kubaj12.online_store.cartordering.application.CheckoutDetails(
                "a".repeat(101), good.contactName(), good.contactPhone(), good.deliveryAddress()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new io.github.kubaj12.online_store.cartordering.application.CheckoutDetails(
                null, " ", good.contactPhone(), good.deliveryAddress()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new io.github.kubaj12.online_store.cartordering.application.CheckoutDetails(
                null, "Jan\nKowalski", good.contactPhone(), good.deliveryAddress()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CheckoutForm form(String phone) {
        var form = new CheckoutForm();
        form.setContactName("Jan Kowalski");
        form.setContactPhone(phone);
        form.setStreet("Prosta");
        form.setBuildingNumber("1");
        form.setPostalCode("00-001");
        form.setCity("Warszawa");
        return form;
    }
}
