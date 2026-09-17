package io.github.kubaj12.online_store.shared.web.error;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.github.kubaj12.online_store.shared.web.request.HtmxHeaders;
import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@BrowserMvcTest(controllers = SharedWebTestController.class)
@WithUserDetails("customer@example.test")
class LocalizedWebExceptionHandlerTests {
	private static final Pattern ERROR_REFERENCE = Pattern.compile("Identyfikator błędu: ([0-9a-f-]{36})");

	@Autowired
	private MockMvc mockMvc;

	@Test
	void rendersLocalizedNotFoundForExpectedAndMissingResources() throws Exception {
		assertPolishError("/test/errors/not-found", 404, "Nie znaleziono strony", "Żądana strona nie istnieje");
		assertPolishError("/route-that-does-not-exist", 404, "Nie znaleziono strony", "Żądana strona nie istnieje");
	}

	@Test
	void rendersLocalizedForbiddenWithoutInternalReason() throws Exception {
		String html = assertPolishError("/test/errors/forbidden", 403, "Brak dostępu", "Nie masz uprawnień");

		assertThat(html).doesNotContain("internal authorization reason");
	}

	@Test
	void rendersLocalizedValidationForExplicitAndBindingFailures() throws Exception {
		assertPolishError("/test/errors/validation", 400, "Nie udało się przetworzyć danych", "Sprawdź przesłane dane");
		assertPolishError("/test/errors/type-mismatch?quantity=nie-liczba", 400,
				"Nie udało się przetworzyć danych", "Sprawdź przesłane dane");
		assertPolishError("/test/errors/method-validation?quantity=0", 400,
				"Nie udało się przetworzyć danych", "Sprawdź przesłane dane");
	}

	@Test
	void treatsControllerReturnValueValidationAsAnUnexpectedServerFailure() throws Exception {
		String html = assertPolishError("/test/errors/return-validation", 500,
				"Coś poszło nie tak", "Wystąpił nieoczekiwany błąd");

		assertThat(html).containsPattern("Identyfikator błędu: [0-9a-f-]{36}");
	}

	@Test
	void rendersLocalizedConflict() throws Exception {
		assertPolishError("/test/errors/conflict", 409, "Nie można zapisać zmian", "Dane zostały w międzyczasie zmienione");
	}

	@Test
	void rendersSanitizedUnexpectedErrorWithReference() throws Exception {
		String html = assertPolishError("/test/errors/unexpected", 500, "Coś poszło nie tak", "Wystąpił nieoczekiwany błąd");

		assertThat(html)
				.containsPattern("Identyfikator błędu: [0-9a-f-]{36}")
				.doesNotContain("tajny-szczegół-techniczny")
				.doesNotContain("IllegalStateException")
				.doesNotContain("stacktrace");
	}

	@Test
	void preservesFramework5xxStatusAndLogsSanitizedDiagnosticContext() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(LocalizedWebExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			String html = assertPolishError("/test/errors/service-unavailable", 503,
					"Coś poszło nie tak", "Wystąpił nieoczekiwany błąd");
			var referenceMatcher = ERROR_REFERENCE.matcher(html);
			assertThat(referenceMatcher.find()).isTrue();
			String errorReference = referenceMatcher.group(1);
			String logMessages = appender.list.stream()
					.map(ILoggingEvent::getFormattedMessage)
					.reduce("", (left, right) -> left + "\n" + right);

			assertThat(html)
					.containsPattern("Identyfikator błędu: [0-9a-f-]{36}")
					.doesNotContain("sensitive upstream detail");
			assertThat(logMessages)
					.contains("reference=" + errorReference)
					.contains("status=503")
					.contains("ResponseStatusException")
					.contains("IllegalArgumentException")
					.contains("SharedWebTestController.serviceUnavailableError")
					.doesNotContain("sensitive upstream detail")
					.doesNotContain("sensitive nested cause");
		}
		finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void rendersExpectedHtmxErrorsAsRetargetedHtmlFragmentsWithoutChangingTheirStatus() throws Exception {
		assertHtmxErrorFragment(
				"/test/errors/validation",
				400,
				"Sprawdź przesłane dane"
		);
		assertHtmxErrorFragment(
				"/test/errors/forbidden",
				403,
				"Nie masz uprawnień"
		);
		assertHtmxErrorFragment(
				"/test/errors/not-found",
				404,
				"Żądana strona nie istnieje"
		);
		assertHtmxErrorFragment(
				"/test/errors/conflict",
				409,
				"Dane zostały w międzyczasie zmienione"
		);
	}

	@Test
	void rendersSanitizedUnexpectedHtmxErrorsAsFragments() throws Exception {
		String html = assertHtmxErrorFragment(
				"/test/errors/unexpected",
				500,
				"Wystąpił nieoczekiwany błąd"
		);

		assertThat(html)
				.containsPattern("Identyfikator błędu: [0-9a-f-]{36}")
				.doesNotContain("tajny-szczegół-techniczny", "IllegalStateException");
	}

	@Test
	void excludesSensitiveExceptionAndRequestDataFromFullPageAndHtmxDiagnostics() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(LocalizedWebExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			String fullPage = mockMvc.perform(get("/test/errors/sensitive?token=url-token")
					.header("Authorization", "Bearer header-secret"))
				.andExpect(status().isInternalServerError()).andReturn().getResponse()
					.getContentAsString(StandardCharsets.UTF_8);
			String htmx = mockMvc.perform(get("/test/errors/sensitive?token=url-token")
					.header(HtmxHeaders.REQUEST, "true")
					.header("Cookie", "B2BSESSION=session-secret"))
				.andExpect(status().isInternalServerError()).andReturn().getResponse()
					.getContentAsString(StandardCharsets.UTF_8);
			String diagnostics = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
					.reduce("", (left, right) -> left + "\n" + right);
			assertThat(fullPage).contains("Identyfikator błędu").doesNotContain("raw-password", "url-token");
			assertThat(htmx).contains("Wystąpił nieoczekiwany błąd").doesNotContain("raw-password", "url-token");
			assertThat(diagnostics).contains("IllegalStateException", "IllegalArgumentException")
					.doesNotContain("raw-password", "synthetic-hash", "invitation-token", "reset-token",
							"smtp-credential", "url-token", "header-secret", "session-secret");
			assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
		}
		finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	private String assertPolishError(String path, int statusCode, String title, String message) throws Exception {
		MvcResult result = mockMvc.perform(get(path))
				.andExpect(status().is(statusCode))
				.andReturn();
		String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(html)
				.contains("<html lang=\"pl\"")
				.contains(">" + statusCode + "</p>")
				.contains(title)
				.contains(message)
				.doesNotContain("Whitelabel Error Page");
		return html;
	}

	private String assertHtmxErrorFragment(String path, int statusCode, String message) throws Exception {
		MvcResult result = mockMvc.perform(get(path).header(HtmxHeaders.REQUEST, "true"))
				.andExpect(status().is(statusCode))
				.andReturn();
		String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(result.getResponse().getHeader(HtmxHeaders.HANDLED_ERROR)).isEqualTo("true");
		assertThat(result.getResponse().getHeader(HtmxHeaders.RETARGET)).isEqualTo("#main-content");
		assertThat(result.getResponse().getHeader(HtmxHeaders.RESWAP)).isEqualTo("innerHTML");
		assertThat(html)
				.contains(message, "data-browser-error")
				.doesNotContain("<!DOCTYPE html>", "<html lang=\"pl\"", "<nav");
		return html;
	}

}
