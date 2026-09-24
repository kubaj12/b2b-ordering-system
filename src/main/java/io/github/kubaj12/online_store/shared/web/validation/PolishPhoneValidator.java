package io.github.kubaj12.online_store.shared.web.validation;

import io.github.kubaj12.online_store.shared.validation.PolishPhoneNumber;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class PolishPhoneValidator implements ConstraintValidator<ValidPolishPhone, CharSequence> {
	@Override
	public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
		return value == null || value.toString().isBlank() || PolishPhoneNumber.isValid(value.toString());
	}
}
