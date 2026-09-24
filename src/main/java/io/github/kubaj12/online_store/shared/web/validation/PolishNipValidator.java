package io.github.kubaj12.online_store.shared.web.validation;

import io.github.kubaj12.online_store.shared.validation.PolishNip;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class PolishNipValidator implements ConstraintValidator<ValidPolishNip, CharSequence> {
	@Override
	public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
		return value == null || PolishNip.isValid(value.toString());
	}
}
