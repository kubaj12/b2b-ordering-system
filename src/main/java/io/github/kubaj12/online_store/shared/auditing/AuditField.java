package io.github.kubaj12.online_store.shared.auditing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * An event-specific definition of one safe audit metadata field.
 *
 * <p>Only enums, bounded numbers, and booleans can be represented. There is deliberately no text
 * field: passwords, credentials, and raw invitation or reset tokens must never cross this API.</p>
 */
public final class AuditField<T> {

	static final int MAX_FIELD_LENGTH = 80;
	static final int MAX_ENCODED_VALUE_LENGTH = 512;

	private static final Pattern FIELD_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9_.-]*$");
	private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");
	private static final Set<String> SENSITIVE_TERMS = Set.of(
			"authorization",
			"code",
			"cookie",
			"credential",
			"hash",
			"password",
			"secret",
			"session",
			"token"
	);

	private final String name;
	private final Class<T> valueType;
	private final Consumer<T> validator;
	private final Function<T, String> encoder;

	private AuditField(
			String name,
			Class<T> valueType,
			Consumer<T> validator,
			Function<T, String> encoder
	) {
		this.name = validateName(name);
		this.valueType = Objects.requireNonNull(valueType, "valueType must not be null");
		this.validator = Objects.requireNonNull(validator, "validator must not be null");
		this.encoder = Objects.requireNonNull(encoder, "encoder must not be null");
	}

	public static <E extends Enum<E>> AuditField<E> enumeration(String name, Class<E> enumType) {
		Objects.requireNonNull(enumType, "enumType must not be null");
		if (!enumType.isEnum()) {
			throw new IllegalArgumentException("enumType must be an enum");
		}
		return new AuditField<>(name, enumType, value -> { }, Enum::name);
	}

	public static AuditField<BigDecimal> decimal(
			String name,
			int scale,
			BigDecimal minimum,
			BigDecimal maximum
	) {
		Objects.requireNonNull(minimum, "minimum must not be null");
		Objects.requireNonNull(maximum, "maximum must not be null");
		if (scale < 0 || scale > 12) {
			throw new IllegalArgumentException("scale must be between 0 and 12");
		}
		if (minimum.compareTo(maximum) > 0) {
			throw new IllegalArgumentException("minimum must not exceed maximum");
		}

		Consumer<BigDecimal> validator = value -> {
			if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
				throw new IllegalArgumentException("audit decimal value is outside the allowed range");
			}
			try {
				value.setScale(scale, RoundingMode.UNNECESSARY);
			}
			catch (ArithmeticException exception) {
				throw new IllegalArgumentException("audit decimal value exceeds the allowed scale", exception);
			}
		};
		Function<BigDecimal, String> encoder = value -> value
				.setScale(scale, RoundingMode.UNNECESSARY)
				.toPlainString();
		return new AuditField<>(name, BigDecimal.class, validator, encoder);
	}

	public static AuditField<Integer> integer(String name, int minimum, int maximum) {
		if (minimum > maximum) {
			throw new IllegalArgumentException("minimum must not exceed maximum");
		}
		Consumer<Integer> validator = value -> {
			if (value < minimum || value > maximum) {
				throw new IllegalArgumentException("audit integer value is outside the allowed range");
			}
		};
		return new AuditField<>(name, Integer.class, validator, String::valueOf);
	}

	public static AuditField<Boolean> flag(String name) {
		return new AuditField<>(name, Boolean.class, value -> { }, String::valueOf);
	}

	public AuditFieldChange<T> change(T from, T to) {
		if (Objects.equals(from, to)) {
			throw new IllegalArgumentException("audit change must alter the value");
		}
		return new AuditFieldChange<>(this, encode(from), encode(to));
	}

	String name() {
		return name;
	}

	private String encode(T value) {
		if (value == null) {
			return null;
		}
		if (!valueType.isInstance(value)) {
			throw new IllegalArgumentException("audit value has the wrong type");
		}

		validator.accept(value);
		String encoded = encoder.apply(value);
		if (encoded.length() > MAX_ENCODED_VALUE_LENGTH) {
			throw new IllegalArgumentException("audit value exceeds the maximum encoded length");
		}
		return encoded;
	}

	private static String validateName(String name) {
		Objects.requireNonNull(name, "name must not be null");
		if (name.length() > MAX_FIELD_LENGTH || !FIELD_PATTERN.matcher(name).matches()) {
			throw new IllegalArgumentException("invalid audit metadata field: " + name);
		}

		String normalized = NON_ALPHANUMERIC.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
		if (SENSITIVE_TERMS.stream().anyMatch(normalized::contains)) {
			throw new IllegalArgumentException("sensitive audit metadata field is not allowed: " + name);
		}
		return name;
	}

}
