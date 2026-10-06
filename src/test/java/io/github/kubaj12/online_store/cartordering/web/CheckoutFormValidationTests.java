package io.github.kubaj12.online_store.cartordering.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

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
