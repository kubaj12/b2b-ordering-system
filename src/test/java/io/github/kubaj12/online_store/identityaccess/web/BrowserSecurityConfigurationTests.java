package io.github.kubaj12.online_store.identityaccess.web;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.github.kubaj12.online_store.shared.web.request.HtmxHeaders;
import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;
import io.github.kubaj12.online_store.testsupport.SecurityTestUsers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@BrowserMvcTest(controllers = SecurityProbeController.class)
@Import(SecurityProbeService.class)
@ActiveProfiles("mvc-security-test")
class BrowserSecurityConfigurationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SecurityProbeService service;

	@BeforeEach
	void resetService() {
		service.reset();
	}

	@Test
	void deniesAnonymousRequestsUsingTransportAppropriateLoginNavigation() throws Exception {
		mockMvc.perform(get("/test/security/authenticated"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/login"));
		mockMvc.perform(get("/test/security/authenticated").header(HtmxHeaders.REQUEST, "true"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HtmxHeaders.REDIRECT, "/login"))
				.andExpect(header().doesNotExist(HttpHeaders.LOCATION))
				.andExpect(content().string(""));
		mockMvc.perform(get("/test/security/authenticated")
					.header(HtmxHeaders.HISTORY_RESTORE_REQUEST, "true"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HtmxHeaders.REDIRECT, "/login"))
				.andExpect(content().string(""));

		assertThat(service.calls()).isZero();
	}

	@Test
	void permitsOnlyTheDeclaredAnonymousPagesAndStaticResources() throws Exception {
		mockMvc.perform(get("/login"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/invitations/accept/test-token"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/password-reset/test-token"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/css/application.css"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/webjars/bootstrap/css/bootstrap.min.css"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/invitations/manage"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/login"));
		mockMvc.perform(get("/password-reset-management"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/login"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("roleMatrix")
	void enforcesTheCompleteRoleMatrix(
			String description,
			RequestPostProcessor principal,
			String path,
			boolean allowed
	) throws Exception {
		var result = mockMvc.perform(get(path).with(principal));
		if (allowed) {
			result.andExpect(status().isOk());
			assertThat(service.calls()).isOne();
		}
		else {
			result.andExpect(status().isForbidden());
			assertThat(service.calls()).isZero();
		}
	}

	@Test
	void rendersRoleFailuresThroughTheLocalizedFullPageAndHtmxContracts() throws Exception {
		mockMvc.perform(get("/test/security/staff").with(SecurityTestUsers.customer()))
				.andExpect(status().isForbidden())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Nie masz uprawnień")));
		mockMvc.perform(get("/test/security/staff")
					.with(SecurityTestUsers.customer())
					.header(HtmxHeaders.REQUEST, "true"))
				.andExpect(status().isForbidden())
				.andExpect(header().string(HtmxHeaders.HANDLED_ERROR, "true"))
				.andExpect(header().string(HtmxHeaders.RETARGET, "#main-content"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Nie masz uprawnień")));

		assertThat(service.calls()).isZero();
	}

	@Test
	void requiresCsrfForAuthenticatedFormAndHtmxCommands() throws Exception {
		mockMvc.perform(post("/test/security/command").with(SecurityTestUsers.customer()))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/test/security/command")
					.with(SecurityTestUsers.customer())
					.with(csrf().useInvalidToken()))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/test/security/command")
					.with(SecurityTestUsers.customer())
					.with(csrf()))
				.andExpect(status().isOk());
		mockMvc.perform(post("/test/security/command")
					.with(SecurityTestUsers.customer())
					.with(csrf().asHeader())
					.header(HtmxHeaders.REQUEST, "true"))
				.andExpect(status().isOk());

		assertThat(service.calls()).isEqualTo(2);
	}

	@Test
	void keepsAnonymousCommandsBehindCsrfProtection() throws Exception {
		mockMvc.perform(post("/invitations/accept/test-token"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/login"));
		mockMvc.perform(post("/invitations/accept/test-token")
					.header(HtmxHeaders.REQUEST, "true"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HtmxHeaders.REDIRECT, "/login"));
		mockMvc.perform(post("/invitations/accept/test-token").with(csrf()))
				.andExpect(status().isOk());

		assertThat(service.calls()).isOne();
	}

	@Test
	void retainsSpringSecurityBrowserResponseHeaders() throws Exception {
		mockMvc.perform(get("/test/security/authenticated").with(SecurityTestUsers.customer()))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL,
						org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"));
	}

	private static Stream<Arguments> roleMatrix() {
		return Stream.of(
				Arguments.of("customer accesses customer route", SecurityTestUsers.customer(),
						"/test/security/customer", true),
				Arguments.of("customer cannot access staff route", SecurityTestUsers.customer(),
						"/test/security/staff", false),
				Arguments.of("customer cannot access administrator route", SecurityTestUsers.customer(),
						"/test/security/admin", false),
				Arguments.of("employee cannot access customer route", SecurityTestUsers.employee(),
						"/test/security/customer", false),
				Arguments.of("employee accesses staff route", SecurityTestUsers.employee(),
						"/test/security/staff", true),
				Arguments.of("employee cannot access administrator route", SecurityTestUsers.employee(),
						"/test/security/admin", false),
				Arguments.of("administrator cannot access customer route", SecurityTestUsers.administrator(),
						"/test/security/customer", false),
				Arguments.of("administrator accesses staff route", SecurityTestUsers.administrator(),
						"/test/security/staff", true),
				Arguments.of("administrator accesses administrator route", SecurityTestUsers.administrator(),
						"/test/security/admin", true)
		);
	}

}
