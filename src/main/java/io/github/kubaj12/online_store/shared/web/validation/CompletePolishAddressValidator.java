package io.github.kubaj12.online_store.shared.web.validation;

import io.github.kubaj12.online_store.shared.validation.PolishAddress;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class CompletePolishAddressValidator implements ConstraintValidator<CompletePolishAddress, PolishAddressFields> {
	@Override
	public boolean isValid(PolishAddressFields value, ConstraintValidatorContext context) {
		if (value == null) {
			return true;
		}
		try {
			PolishAddress.of(value.street(), value.buildingNumber(), value.unitNumber(),
					value.postalCode(), value.city(), value.country());
			return true;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}
}
