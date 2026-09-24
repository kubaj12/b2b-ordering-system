package io.github.kubaj12.online_store.shared.web.validation;

import io.github.kubaj12.online_store.shared.validation.PolishPostalCode;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class PolishPostalCodeValidator implements ConstraintValidator<ValidPolishPostalCode, CharSequence> {
	@Override
	public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
		return value == null || PolishPostalCode.isValid(value.toString());
	}
}
