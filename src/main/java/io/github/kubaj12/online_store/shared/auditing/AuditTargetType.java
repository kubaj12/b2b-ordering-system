package io.github.kubaj12.online_store.shared.auditing;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * An event-bound target definition that accepts only an internal UUID or positive numeric ID.
 *
 * <p>There is deliberately no arbitrary-text target factory, so raw credentials and invitation or
 * reset tokens cannot be used as audit target IDs.</p>
 */
public final class AuditTargetType<T> {

	static final int MAX_TYPE_LENGTH = 80;

	private static final Pattern TYPE_FORMAT = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_-]*)*$");
	private static final UUID NIL_UUID = new UUID(0, 0);

	private final String value;
	private final Class<T> idType;
	private final Consumer<T> validator;
	private final Function<T, String> encoder;

	private AuditTargetType(
			String value,
			Class<T> idType,
			Consumer<T> validator,
			Function<T, String> encoder
	) {
		this.value = validateType(value);
		this.idType = Objects.requireNonNull(idType, "idType must not be null");
		this.validator = Objects.requireNonNull(validator, "validator must not be null");
		this.encoder = Objects.requireNonNull(encoder, "encoder must not be null");
	}

	public static AuditTargetType<UUID> uuid(String type) {
		return new AuditTargetType<>(type, UUID.class, id -> {
			if (NIL_UUID.equals(id)) {
				throw new IllegalArgumentException("audit target UUID must not be nil");
			}
		}, UUID::toString);
	}

	public static AuditTargetType<Long> positiveLong(String type) {
		return new AuditTargetType<>(type, Long.class, id -> {
			if (id <= 0) {
				throw new IllegalArgumentException("audit target numeric ID must be positive");
			}
		}, String::valueOf);
	}

	String value() {
		return value;
	}

	String encode(T id) {
		Objects.requireNonNull(id, "audit target ID must not be null");
		if (!idType.isInstance(id)) {
			throw new IllegalArgumentException("audit target ID has the wrong type");
		}
		validator.accept(id);
		return encoder.apply(id);
	}

	private static String validateType(String type) {
		Objects.requireNonNull(type, "type must not be null");
		if (type.length() > MAX_TYPE_LENGTH || !TYPE_FORMAT.matcher(type).matches()) {
			throw new IllegalArgumentException("invalid audit target type: " + type);
		}
		return type;
	}

}
