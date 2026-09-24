package io.github.kubaj12.online_store.shared.web.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Documented
@Constraint(validatedBy = CompletePolishAddressValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CompletePolishAddress {
	String message() default "{validation.complete-polish-address}";
	Class<?>[] groups() default {};
	Class<? extends Payload>[] payload() default {};
}
