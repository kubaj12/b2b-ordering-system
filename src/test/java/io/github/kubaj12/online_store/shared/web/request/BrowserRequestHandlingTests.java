package io.github.kubaj12.online_store.shared.web.request;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@BrowserMvcTest(controllers = {
	BrowserRequestTestController.class,
	AnonymousBrowserRouteTestController.class
})
@Import(BrowserRequestTestApplicationService.class)
class BrowserRequestHandlingTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BrowserRequestTestApplicationService applicationService;

	@BeforeEach
	void resetApplicationService() {
		applicationService.reset();
	}

	@Test
	@WithUserDetails("employee@example.test")
	void rendersOneServiceResultAsAFullPageOrFragment() throws Exception {
		applicationService.respondWith("wspólna-wartość");

		MvcResult pageResult = mockMvc.perform(get("/test/browser-flow"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.VARY, "HX-Request, HX-History-Restore-Request"))
				.andReturn();
		MvcResult fragmentResult = mockMvc.perform(get("/test/browser-flow")
					.header(HtmxHeaders.REQUEST, "true"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.VARY, "HX-Request, HX-History-Restore-Request"))
				.andReturn();

		assertThat(body(pageResult))
				.contains("<!DOCTYPE html>", "<html lang=\"pl\"", "wspólna-wartość", "name=\"_csrf\"");
		assertThat(body(fragmentResult))
				.contains("id=\"flow-content\"", "wspólna-wartość")
				.doesNotContain("<!DOCTYPE html>", "<html lang=\"pl\"", "<nav");
		assertThat(applicationService.loadCount()).isEqualTo(2);
	}

	@Test
	@WithUserDetails("employee@example.test")
	void rendersACompletePageForAnHtmxHistoryRestoreRequest() throws Exception {
		applicationService.respondWith("odtworzona-wartość");

		MvcResult result = mockMvc.perform(get("/test/browser-flow")
					.header(HtmxHeaders.REQUEST, "true")
					.header(HtmxHeaders.HISTORY_RESTORE_REQUEST, "true"))
				.andExpect(status().isOk())
				.andReturn();

		assertThat(body(result)).contains("<!DOCTYPE html>", "<html lang=\"pl\"", "odtworzona-wartość");
		assertThat(applicationService.loadCount()).isOne();
	}

	@Test
	@WithUserDetails("employee@example.test")
	void returnsTheSameValidationStatusAndErrorsWithoutCallingTheService() throws Exception {
		MvcResult pageResult = mockMvc.perform(post("/test/browser-flow")
					.with(csrf())
					.param("name", ""))
				.andExpect(status().isBadRequest())
				.andReturn();
		MvcResult fragmentResult = mockMvc.perform(post("/test/browser-flow")
					.header(HtmxHeaders.REQUEST, "true")
					.with(csrf().asHeader())
					.param("name", ""))
				.andExpect(status().isBadRequest())
				.andExpect(header().string(HtmxHeaders.HANDLED_ERROR, "true"))
				.andReturn();

		assertThat(body(pageResult)).contains("<html lang=\"pl\"", "Pole jest wymagane.");
		assertThat(body(fragmentResult))
				.contains("id=\"flow-content\"", "Pole jest wymagane.")
				.doesNotContain("<html lang=\"pl\"");
		assertThat(applicationService.submissions()).isEmpty();
	}

	@Test
	@WithUserDetails("employee@example.test")
	void redirectsAfterOneSuccessfulServiceCallUsingTheAppropriateBrowserTransport() throws Exception {
		mockMvc.perform(post("/test/browser-flow")
					.with(csrf())
					.param("name", "pełna"))
				.andExpect(status().isSeeOther())
				.andExpect(header().string(HttpHeaders.LOCATION, "/test/browser-flow"));
		mockMvc.perform(post("/test/browser-flow")
					.header(HtmxHeaders.REQUEST, "true")
					.with(csrf().asHeader())
					.param("name", "fragment"))
				.andExpect(status().isNoContent())
				.andExpect(header().string(HtmxHeaders.REDIRECT, "/test/browser-flow"));

		assertThat(applicationService.submissions()).containsExactly("pełna", "fragment");
	}

	@Test
	@WithUserDetails("employee@example.test")
	void rejectsMissingOrInvalidCsrfBeforeEitherRequestPathCanCallTheService() throws Exception {
		MvcResult pageResult = mockMvc.perform(post("/test/browser-flow").param("name", "pełna"))
				.andExpect(status().isForbidden())
				.andReturn();
		MvcResult fragmentResult = mockMvc.perform(post("/test/browser-flow")
					.header(HtmxHeaders.REQUEST, "true")
					.param("name", "fragment"))
				.andExpect(status().isForbidden())
				.andExpect(header().string(HtmxHeaders.HANDLED_ERROR, "true"))
				.andExpect(header().string(HtmxHeaders.RETARGET, "#main-content"))
				.andExpect(header().string(HtmxHeaders.RESWAP, "innerHTML"))
				.andReturn();
		mockMvc.perform(post("/test/browser-flow")
					.with(csrf().useInvalidToken())
					.param("name", "błędny-token-formularza"))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/test/browser-flow")
					.header(HtmxHeaders.REQUEST, "true")
					.with(csrf().asHeader().useInvalidToken())
					.param("name", "błędny-token-htmx"))
				.andExpect(status().isForbidden())
				.andExpect(header().string(HtmxHeaders.HANDLED_ERROR, "true"));

		assertThat(body(pageResult)).contains("<html lang=\"pl\"", "Nie masz uprawnień");
		assertThat(body(fragmentResult))
				.contains("Nie masz uprawnień")
				.doesNotContain("<html lang=\"pl\"");
		assertThat(applicationService.submissions()).isEmpty();
	}

	@Test
	@WithUserDetails("customer@example.test")
	void appliesTheSameRoleAuthorizationBeforeEitherRepresentationLoadsData() throws Exception {
		mockMvc.perform(get("/test/browser-flow"))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/test/browser-flow").header(HtmxHeaders.REQUEST, "true"))
				.andExpect(status().isForbidden())
				.andExpect(header().string(HtmxHeaders.HANDLED_ERROR, "true"));

		assertThat(applicationService.loadCount()).isZero();
	}

	@Test
	void startsLoginWithAFullNavigationForUnauthenticatedRequests() throws Exception {
		mockMvc.perform(get("/test/browser-flow"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/login"));
		mockMvc.perform(get("/test/browser-flow").header(HtmxHeaders.REQUEST, "true"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HtmxHeaders.REDIRECT, "/login"))
				.andExpect(header().doesNotExist(HttpHeaders.LOCATION))
				.andExpect(content().string(""));

		assertThat(applicationService.loadCount()).isZero();
	}

	@Test
	void startsLoginForAnUnauthenticatedHtmxPostRejectedByCsrf() throws Exception {
		mockMvc.perform(post("/test/browser-flow")
					.header(HtmxHeaders.REQUEST, "true")
					.param("name", "wygasła-sesja"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HtmxHeaders.REDIRECT, "/login"))
				.andExpect(header().doesNotExist(HtmxHeaders.HANDLED_ERROR));

		assertThat(applicationService.submissions()).isEmpty();
	}

	@Test
	void returnsANonSwappableLoginRedirectForAnExpiredHistoryRestoration() throws Exception {
		mockMvc.perform(get("/test/browser-flow")
					.header(HtmxHeaders.HISTORY_RESTORE_REQUEST, "true"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HtmxHeaders.REDIRECT, "/login"))
				.andExpect(header().doesNotExist(HtmxHeaders.HANDLED_ERROR))
				.andExpect(content().string(""));

		assertThat(applicationService.loadCount()).isZero();
	}

	@Test
	void permitsOnlyTheRequiredAnonymousPageNamespaces() throws Exception {
		mockMvc.perform(get("/login"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/invitations/accept/test-token"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString(
						"/invitations/accept/test-token"
				)));
		mockMvc.perform(get("/password-reset"))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("/password-reset")));
		mockMvc.perform(get("/error/required-page"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/login"));

		mockMvc.perform(get("/invitations/manage"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	void servesBrowserAssetsWithoutAuthentication() throws Exception {
		mockMvc.perform(get("/css/application.css"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/js/application.js"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/webjars/bootstrap/css/bootstrap.min.css"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/webjars/htmx.org/dist/htmx.min.js"))
				.andExpect(status().isOk());
	}

	@Test
	@WithUserDetails("employee@example.test")
	void doesNotExposeAJsonRepresentation() throws Exception {
		mockMvc.perform(get("/test/browser-flow").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isNotAcceptable())
				.andExpect(header().doesNotExist(HttpHeaders.CONTENT_TYPE));
		assertThat(applicationService.loadCount()).isZero();
	}

	private static String body(MvcResult result) {
		return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

}
