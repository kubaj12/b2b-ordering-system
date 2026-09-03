package io.github.kubaj12.online_store.shared.web.error;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@ControllerAdvice
public final class LocalizedWebExceptionHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(LocalizedWebExceptionHandler.class);

	private final MessageSource messageSource;

	public LocalizedWebExceptionHandler(MessageSource messageSource) {
		this.messageSource = messageSource;
	}

	@ExceptionHandler(WebErrorException.class)
	ModelAndView handleExpectedError(WebErrorException exception, Locale locale) {
		return errorView(
				exception.status(),
				exception.messageCode(),
				exception.messageArguments(),
				locale
		);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ModelAndView handleAccessDenied(Locale locale) {
		return errorView(HttpStatus.FORBIDDEN, "error.403.message", new Object[0], locale);
	}

	@ExceptionHandler({ NoHandlerFoundException.class, NoResourceFoundException.class })
	ModelAndView handleNotFound(Locale locale) {
		return errorView(HttpStatus.NOT_FOUND, "error.404.message", new Object[0], locale);
	}

	@ExceptionHandler({
			BindException.class,
			ConstraintViolationException.class,
			MethodArgumentTypeMismatchException.class,
			MissingServletRequestParameterException.class
	})
	ModelAndView handleRequestValidation(Locale locale) {
		return errorView(HttpStatus.BAD_REQUEST, "error.400.message", new Object[0], locale);
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ModelAndView handleMethodValidation(HandlerMethodValidationException exception, Locale locale) {
		if (exception.isForReturnValue()) {
			return unexpectedErrorView(exception, exception.getStatusCode(), locale);
		}
		return frameworkErrorView(exception.getStatusCode(), locale);
	}

	@ExceptionHandler(RuntimeException.class)
	ModelAndView handleUnexpected(RuntimeException exception, Locale locale) {
		if (exception instanceof ErrorResponse errorResponse) {
			if (errorResponse.getStatusCode().is5xxServerError()) {
				return unexpectedErrorView(exception, errorResponse.getStatusCode(), locale);
			}
			return frameworkErrorView(errorResponse.getStatusCode(), locale);
		}
		return unexpectedErrorView(exception, HttpStatus.INTERNAL_SERVER_ERROR, locale);
	}

	private ModelAndView unexpectedErrorView(
			RuntimeException exception,
			HttpStatusCode status,
			Locale locale
	) {
		String errorReference = UUID.randomUUID().toString();
		LOGGER.error(
				"Unexpected web request failure, reference={}, status={}, exceptionTypes={}, stackFrames={}",
				errorReference,
				status.value(),
				exceptionTypes(exception),
				stackFrames(exception)
		);

		ModelAndView view = errorView(status, "error.500.message", new Object[0], locale);
		view.addObject("errorReference", errorReference);
		return view;
	}

	private ModelAndView frameworkErrorView(HttpStatusCode statusCode, Locale locale) {
		return errorView(statusCode, defaultMessageCode(statusCode), new Object[0], locale);
	}

	private ModelAndView errorView(
			HttpStatusCode status,
			String messageCode,
			Object[] messageArguments,
			Locale locale
	) {
		String defaultMessage = messageSource.getMessage(defaultMessageCode(status), null, locale);
		String localizedMessage = messageSource.getMessage(
				messageCode,
				messageArguments,
				defaultMessage,
				locale
		);
		ModelAndView view = new ModelAndView(viewName(status));
		view.setStatus(status);
		view.addObject("status", status.value());
		view.addObject("errorMessage", localizedMessage);
		return view;
	}

	private static String defaultMessageCode(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "error.400.message";
			case 403 -> "error.403.message";
			case 404 -> "error.404.message";
			case 409 -> "error.409.message";
			default -> status.is4xxClientError() ? "error.400.message" : "error.500.message";
		};
	}

	private static String viewName(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "error/400";
			case 403 -> "error/403";
			case 404 -> "error/404";
			case 409 -> "error/409";
			default -> status.is4xxClientError() ? "error/400" : "error/500";
		};
	}

	private static List<String> exceptionTypes(Throwable exception) {
		List<String> types = new ArrayList<>();
		for (Throwable current : throwableChain(exception)) {
			types.add(current.getClass().getName());
		}
		return List.copyOf(types);
	}

	private static List<String> stackFrames(Throwable exception) {
		List<String> frames = new ArrayList<>();
		for (Throwable current : throwableChain(exception)) {
			String type = current.getClass().getName();
			StackTraceElement[] trace = current.getStackTrace();
			for (int index = 0; index < trace.length && index < 8; index++) {
				frames.add(type + " at " + trace[index]);
			}
		}
		return List.copyOf(frames);
	}

	private static List<Throwable> throwableChain(Throwable exception) {
		List<Throwable> chain = new ArrayList<>();
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Throwable current = exception;
		while (current != null && chain.size() < 4 && visited.add(current)) {
			chain.add(current);
			current = current.getCause();
		}
		return chain;
	}

}
