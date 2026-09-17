package io.github.kubaj12.online_store.identityaccess.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;

import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;
import io.github.kubaj12.online_store.testsupport.InMemoryAuthenticationAccountStore;
import io.github.kubaj12.online_store.testsupport.SecurityTestUsers;
import io.github.kubaj12.online_store.shared.web.request.HtmxHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@BrowserMvcTest(controllers = LoginController.class)
class BrowserFormLoginTests {

	@Autowired private MockMvc mockMvc;
	@Autowired private InMemoryAuthenticationAccountStore accounts;
	@Autowired private org.springframework.security.crypto.password.PasswordEncoder encoder;

	@BeforeEach
	void resetStore() { accounts.reset(encoder); }

	@AfterEach
	void resetStoreAfterTest() { accounts.reset(encoder); }

	@Test
	void rendersPolishLoginFormWithSafeFieldsAndCsrf() throws Exception {
		mockMvc.perform(get("/login")).andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"email\"")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"password\"")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Zaloguj się")));
	}

	@Test
	void anonymousHtmxLoginUsesAFullNavigationRedirect() throws Exception {
		mockMvc.perform(get("/login").header(HtmxHeaders.REQUEST, "true"))
				.andExpect(status().isNoContent()).andExpect(header().string(HtmxHeaders.REDIRECT, "/login"))
				.andExpect(header().doesNotExist(HttpHeaders.LOCATION)).andExpect(content().string(""));
		mockMvc.perform(get("/login").header(HtmxHeaders.HISTORY_RESTORE_REQUEST, "true"))
				.andExpect(status().isConflict()).andExpect(header().string(HtmxHeaders.REDIRECT, "/login"))
				.andExpect(content().string(""));
	}

	@Test
	void publicLoginClearsAnInvalidAuthenticatedPrincipalBeforeRendering() throws Exception {
		accounts.put(new io.github.kubaj12.online_store.identityaccess.application.AuthenticationAccountStore.Credentials(
				InMemoryAuthenticationAccountStore.CUSTOMER_ID, "customer@example.test",
				encoder.encode("CorrectHorseBattery12"), "CUSTOMER", "BLOCKED", 0));
		mockMvc.perform(get("/login").with(SecurityTestUsers.customer()))
				.andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"email\"")));
	}

	@Test
	void accountLookupFailureRendersLocalizedServerErrorAndClearsAuthentication() throws Exception {
		accounts.failNextLookup(new IllegalStateException("secret SQL password"));
		mockMvc.perform(get("/").with(SecurityTestUsers.customer()))
				.andExpect(status().isInternalServerError())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Coś poszło nie tak")))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret SQL password"))));
		accounts.failNextLookup(new IllegalStateException("secret SQL password"));
		mockMvc.perform(get("/").with(SecurityTestUsers.customer()).header(HtmxHeaders.REQUEST, "true"))
				.andExpect(status().isInternalServerError())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string(HtmxHeaders.HANDLED_ERROR, "true"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Coś poszło nie tak")));
		accounts.failNextLookup(new IllegalStateException("secret SQL password"));
		mockMvc.perform(get("/").with(SecurityTestUsers.customer())
				.header(HtmxHeaders.HISTORY_RESTORE_REQUEST, "true"))
				.andExpect(status().isInternalServerError())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Coś poszło nie tak")));
	}

	@Test
	void authenticatesCanonicalEmailAndUsesFixedHomeDestination() throws Exception {
		MvcResult result = mockMvc.perform(post("/login").with(csrf())
				.param("email", "  EMPLOYEE@EXAMPLE.TEST ")
				.param("password", "CorrectHorseBattery12"))
				.andExpect(status().isSeeOther()).andExpect(header().string(HttpHeaders.LOCATION, "/")).andReturn();
		assertThat(result.getRequest().getSession(false)).isNotNull();
	}

	@Test
	void rejectsUnknownOrBlockedCredentialsWithSameSafeDestination() throws Exception {
		mockMvc.perform(post("/login").with(csrf()).param("email", "missing@example.test")
				.param("password", "CorrectHorseBattery12"))
				.andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?error"));
		accounts.put(new io.github.kubaj12.online_store.identityaccess.application.AuthenticationAccountStore.Credentials(
				InMemoryAuthenticationAccountStore.CUSTOMER_ID, "customer@example.test",
				encoder.encode("CorrectHorseBattery12"), "CUSTOMER", "BLOCKED", 0));
		mockMvc.perform(post("/login").with(csrf()).param("email", "customer@example.test")
				.param("password", "CorrectHorseBattery12"))
				.andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?error"));
	}

	@Test
	void logsOutOnlyThroughPostAndExpiresConfiguredSessionCookie() throws Exception {
		MvcResult login = mockMvc.perform(post("/login").with(csrf())
				.param("email", "customer@example.test").param("password", "CorrectHorseBattery12"))
				.andExpect(status().isSeeOther()).andReturn();
		mockMvc.perform(post("/logout").session((org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false))
				.with(csrf())).andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?logout"))
				.andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
		mockMvc.perform(get("/logout")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
	}
}
