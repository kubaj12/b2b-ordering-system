package io.github.kubaj12.online_store.shared.web.error;

import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

/**
 * An expected browser-facing error raised by a module web adapter.
 *
 * <p>The message code and its arguments are rendered to the user. Callers must therefore use only
 * localization keys and arguments that are explicitly safe for the current authenticated user.</p>
 */
public final class WebErrorException extends RuntimeException {

	private static final long serialVersionUID = 1L;
	private static final Pattern MESSAGE_CODE = Pattern.compile(
			"[a-z][a-z0-9]*(?:[.-][a-z0-9][a-z0-9-]*)+"
	);

	private final HttpStatus status;
	private final String messageCode;
	private final Object[] messageArguments;

	private WebErrorException(HttpStatus status, String messageCode, Object[] messageArguments) {
		super(messageCode);
		this.status = Objects.requireNonNull(status, "web error status must not be null");
		if (!MESSAGE_CODE.matcher(Objects.requireNonNull(messageCode, "web error message code must not be null"))
				.matches()) {
			throw new IllegalArgumentException("web error message code has an invalid format");
		}
		this.messageCode = messageCode;
		this.messageArguments = Objects.requireNonNull(
				messageArguments, "web error message arguments must not be null"
		).clone();
	}

	public static WebErrorException validation() {
		return validation("error.400.message");
	}

	public static WebErrorException validation(String messageCode, Object... messageArguments) {
		return new WebErrorException(HttpStatus.BAD_REQUEST, messageCode, messageArguments);
	}

	public static WebErrorException forbidden() {
		return forbidden("error.403.message");
	}

	public static WebErrorException forbidden(String messageCode, Object... messageArguments) {
		return new WebErrorException(HttpStatus.FORBIDDEN, messageCode, messageArguments);
	}

	public static WebErrorException notFound() {
		return notFound("error.404.message");
	}

	public static WebErrorException notFound(String messageCode, Object... messageArguments) {
		return new WebErrorException(HttpStatus.NOT_FOUND, messageCode, messageArguments);
	}

	public static WebErrorException conflict() {
		return conflict("error.409.message");
	}

	public static WebErrorException conflict(String messageCode, Object... messageArguments) {
		return new WebErrorException(HttpStatus.CONFLICT, messageCode, messageArguments);
	}

	HttpStatus status() {
		return status;
	}

	String messageCode() {
		return messageCode;
	}

	Object[] messageArguments() {
		return messageArguments.clone();
	}

}
