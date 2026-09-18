package io.github.kubaj12.online_store.identityaccess.web;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;
import io.github.kubaj12.online_store.testsupport.InMemoryAuthenticationAccountStore;
import io.github.kubaj12.online_store.testsupport.InMemoryLoginThrottleStore;
import io.github.kubaj12.online_store.testsupport.TestClock;
import io.github.kubaj12.online_store.testsupport.SecurityTestUsers;
import io.github.kubaj12.online_store.shared.web.request.HtmxHeaders;
import io.github.kubaj12.online_store.identityaccess.domain.LoginAttemptKey;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottlePolicy;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottleState;
import io.github.kubaj12.online_store.identityaccess.application.AuthenticationAccountStore.Credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@BrowserMvcTest(controllers = {LoginController.class, SecurityProbeController.class})
@Import(SecurityProbeService.class)
@ActiveProfiles("mvc-security-test")
class BrowserFormLoginTests {

	@Autowired private MockMvc mockMvc;
	@Autowired private InMemoryAuthenticationAccountStore accounts;
	@Autowired private InMemoryLoginThrottleStore throttle;
	@Autowired private TestClock clock;
	@Autowired private LoginThrottlePolicy policy;
	@Autowired private org.springframework.security.crypto.password.PasswordEncoder encoder;

	@BeforeEach
	void resetStore() { accounts.reset(encoder); throttle.reset(); clock.reset(); }

	@AfterEach
	void resetStoreAfterTest() { accounts.reset(encoder); throttle.reset(); clock.reset(); }

	@Test
	void blocksAfterFiveFailuresAndSkipsCredentialLookupWhileBlocked() throws Exception {
		for (int attempt = 0; attempt < 5; attempt++) {
			mockMvc.perform(post("/login").with(csrf()).param("email", "customer@example.test")
					.param("password", "wrong-password"))
				.andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?error"));
		}
		int lookups = accounts.credentialsLookups();
		LoginAttemptKey key = LoginAttemptKey.of("customer@example.test", "127.0.0.1");
		LoginThrottleState blocked = throttle.state(key);
		assertThat(blocked.failedAttempts()).isEqualTo(5);
		assertThat(blocked.blockedUntil()).isEqualTo(clock.instant().plus(policy.blockDuration()));
		for (String password : java.util.List.of("wrong-password", "CorrectHorseBattery12", "wrong-password", "CorrectHorseBattery12")) {
			clock.advance(Duration.ofSeconds(1));
			mockMvc.perform(post("/login").with(csrf()).param("email", "customer@example.test")
					.param("password", password))
				.andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?error"));
			assertThrottleStateUnchanged(key, blocked);
			assertThat(accounts.credentialsLookups()).isEqualTo(lookups);
			assertThat(accounts.successfulLoginWrites()).isZero();
			assertThat(accounts.lastLoginAt(InMemoryAuthenticationAccountStore.CUSTOMER_ID)).isNull();
		}
	}

	@Test
	void correctPasswordIsAdmittedExactlyAtBlockExpiry() throws Exception {
		for (int attempt = 0; attempt < 5; attempt++) postFailure("customer@example.test", "127.0.0.1", null, null, null);
		LoginAttemptKey key = LoginAttemptKey.of("customer@example.test", "127.0.0.1");
		LoginThrottleState blocked = throttle.state(key);
		int lookups = accounts.credentialsLookups();
		clock.set(blocked.blockedUntil().minusNanos(1_000));
		mockMvc.perform(post("/login").with(csrf()).param("email", "customer@example.test")
				.param("password", "CorrectHorseBattery12"))
			.andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?error"));
		assertThrottleStateUnchanged(key, blocked);
		assertThat(accounts.credentialsLookups()).isEqualTo(lookups);
		assertThat(accounts.successfulLoginWrites()).isZero();

		clock.set(blocked.blockedUntil());
		mockMvc.perform(post("/login").with(csrf()).param("email", "customer@example.test")
				.param("password", "CorrectHorseBattery12"))
			.andExpect(status().isSeeOther()).andExpect(redirectedUrl("/")).andExpect(authenticated());
		assertThat(accounts.credentialsLookups()).isEqualTo(lookups + 1);
		assertThat(accounts.successfulLoginWrites()).isOne();
		assertThat(accounts.lastLoginAt(InMemoryAuthenticationAccountStore.CUSTOMER_ID)).isEqualTo(clock.instant());
		LoginThrottleState reset = throttle.state(key);
		assertThat(reset.failedAttempts()).isZero();
		assertThat(reset.lastFailedAt()).isNull();
		assertThat(reset.blockedUntil()).isNull();
		assertThat(reset.windowStartedAt()).isEqualTo(clock.instant());
		assertThat(reset.updatedAt()).isEqualTo(clock.instant());
		assertThat(reset.createdAt()).isEqualTo(blocked.createdAt());
	}

	@Test
	void failedAttemptStartsANewWindowExactlyAtWindowExpiry() throws Exception {
		LoginAttemptKey key = LoginAttemptKey.of("customer@example.test", "127.0.0.1");
		postFailure("customer@example.test", "127.0.0.1", null, null, null);
		LoginThrottleState initial = throttle.state(key);
		Instant windowEnd = initial.windowStartedAt().plus(policy.window());
		clock.set(windowEnd.minusNanos(1_000));
		postFailure("customer@example.test", "127.0.0.1", null, null, null);
		assertThat(throttle.state(key).failedAttempts()).isEqualTo(2);
		assertThat(throttle.state(key).windowStartedAt()).isEqualTo(initial.windowStartedAt());
		clock.set(windowEnd);
		postFailure("customer@example.test", "127.0.0.1", null, null, null);
		LoginThrottleState fresh = throttle.state(key);
		assertThat(fresh.failedAttempts()).isOne();
		assertThat(fresh.windowStartedAt()).isEqualTo(windowEnd);
		assertThat(fresh.lastFailedAt()).isEqualTo(windowEnd);
		assertThat(fresh.updatedAt()).isEqualTo(windowEnd);
		assertThat(fresh.createdAt()).isEqualTo(initial.createdAt());
		assertThat(fresh.blockedUntil()).isNull();
		assertThat(accounts.credentialsLookups()).isEqualTo(3);
		assertThat(accounts.successfulLoginWrites()).isZero();
		assertThat(accounts.lastLoginAt(InMemoryAuthenticationAccountStore.CUSTOMER_ID)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"BLOCKED", "VERSION_CHANGED"})
	void changedAccountAfterPasswordVerificationRejectsCompletionAndInvalidatesSession(String change) throws Exception {
		postFailure("customer@example.test", "127.0.0.1", null, null, null);
		LoginAttemptKey key = LoginAttemptKey.of("customer@example.test", "127.0.0.1");
		LoginThrottleState before = throttle.state(key);
		int lookups = accounts.credentialsLookups();
		MockHttpSession session = new MockHttpSession();
		accounts.beforeNextSuccessfulLogin(() -> {
			assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();
			assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isNotNull();
			accounts.put(new Credentials(InMemoryAuthenticationAccountStore.CUSTOMER_ID, "customer@example.test",
					encoder.encode("CorrectHorseBattery12"), "CUSTOMER",
					"BLOCKED".equals(change) ? "BLOCKED" : "ACTIVE", "VERSION_CHANGED".equals(change) ? 1 : 0));
		});
		MvcResult result = mockMvc.perform(post("/login").session(session).with(csrf())
				.param("email", "customer@example.test").param("password", "CorrectHorseBattery12"))
			.andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?error"))
			.andExpect(unauthenticated()).andReturn();
		assertThat(session.isInvalid()).isTrue();
		assertThat(result.getRequest().getSession(false)).isNull();
		assertThat(accounts.credentialsLookups()).isEqualTo(lookups + 1);
		assertThat(accounts.successfulLoginWrites()).isZero();
		assertThat(accounts.lastLoginAt(InMemoryAuthenticationAccountStore.CUSTOMER_ID)).isNull();
		assertThat(throttle.state(key).failedAttempts()).isEqualTo(before.failedAttempts() + 1);
		assertThat(throttle.state(key).windowStartedAt()).isEqualTo(before.windowStartedAt());
		assertThat(throttle.state(key).blockedUntil()).isNull();
		mockMvc.perform(get("/test/security/authenticated"))
			.andExpect(status().isFound()).andExpect(redirectedUrl("/login")).andExpect(unauthenticated());
		assertThat(throttle.state(key).failedAttempts()).isEqualTo(2);
	}

	@Test
	void completionTimestampFailureClearsTheSavedAuthenticationAndUsesGenericFailure() throws Exception {
		accounts.failNextSuccessfulLogin(new IllegalStateException("synthetic timestamp failure"));
		MvcResult result = mockMvc.perform(post("/login").with(csrf())
				.param("email", "customer@example.test").param("password", "CorrectHorseBattery12"))
			.andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?error")).andReturn();
		assertThat(result.getRequest().getSession(false)).isNull();
		assertThat(accounts.successfulLoginWrites()).isZero();
	}

	@Test
	void missingCsrfDoesNoAdmissionOrTimestampWork() throws Exception {
		int locks = throttle.lockCalls();
		mockMvc.perform(post("/login").param("email", "customer@example.test")
				.param("password", "CorrectHorseBattery12"))
			.andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		assertThat(throttle.lockCalls()).isEqualTo(locks);
		assertThat(accounts.successfulLoginWrites()).isZero();
	}

	@Test
	void invalidAndHtmxCsrfDoNoAdmissionOrTimestampWork() throws Exception {
		int locks = throttle.lockCalls();
		mockMvc.perform(post("/login").with(csrf().useInvalidToken()).param("email", "customer@example.test")
				.param("password", "CorrectHorseBattery12"))
			.andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		mockMvc.perform(post("/login").header(HtmxHeaders.REQUEST, "true").param("email", "customer@example.test")
				.param("password", "CorrectHorseBattery12"))
			.andExpect(status().isUnauthorized()).andExpect(header().string(HtmxHeaders.REDIRECT, "/login"));
		assertThat(throttle.lockCalls()).isEqualTo(locks);
		assertThat(accounts.successfulLoginWrites()).isZero();
	}

	@Test
	void resetFailureAfterCredentialVerificationInvalidatesTheSavedSession() throws Exception {
		throttle.failNextSave(new IllegalStateException("synthetic reset failure"));
		MvcResult result = mockMvc.perform(post("/login").with(csrf())
				.param("email", "customer@example.test").param("password", "CorrectHorseBattery12"))
			.andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?error")).andReturn();
		assertThat(result.getRequest().getSession(false)).isNull();
		assertThat(accounts.successfulLoginWrites()).isOne();
	}

	@Test
	void browserLoginFailuresUseTheSameGenericTransportContract() throws Exception {
		for (String email : java.util.List.of("missing@example.test", "customer@example.test", "not an email")) {
			assertGenericFailureAcrossTransports(email, "wrong-password");
		}
		resetStore();
		accounts.put(new io.github.kubaj12.online_store.identityaccess.application.AuthenticationAccountStore.Credentials(
				InMemoryAuthenticationAccountStore.CUSTOMER_ID, "customer@example.test",
				encoder.encode("CorrectHorseBattery12"), "CUSTOMER", "BLOCKED", 0));
		int blockedLookups = accounts.credentialsLookups();
		assertGenericFailureAcrossTransports("customer@example.test", "CorrectHorseBattery12");
		assertThat(accounts.credentialsLookups()).isEqualTo(blockedLookups + 3);
		assertThat(accounts.successfulLoginWrites()).isZero();
		LoginThrottleState blockedAccountState = throttle.state(LoginAttemptKey.of("customer@example.test", "127.0.0.1"));
		assertThat(blockedAccountState.failedAttempts()).isEqualTo(3);
		assertThat(blockedAccountState.blockedUntil()).isNull();
		assertThat(policy.blocked(blockedAccountState, clock.instant())).isFalse();

		resetStore();
		for (int attempt = 0; attempt < 5; attempt++) postFailure("customer@example.test", "127.0.0.1", null, null, null);
		int throttledLookups = accounts.credentialsLookups();
		assertGenericFailureAcrossTransports("customer@example.test", "CorrectHorseBattery12");
		assertThat(accounts.credentialsLookups()).isEqualTo(throttledLookups);
		assertThat(accounts.successfulLoginWrites()).isZero();
		mockMvc.perform(get("/login").param("error", ""))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("Nieprawidłowy adres e-mail lub hasło.")));
	}

	private void assertGenericFailureAcrossTransports(String email, String password) throws Exception {
		for (int transport = 0; transport < 3; transport++) {
			MockHttpServletRequestBuilder request = post("/login").with(csrf()).param("email", email).param("password", password);
			if (transport == 1) request.header(HtmxHeaders.REQUEST, "true");
			if (transport == 2) request.header(HtmxHeaders.HISTORY_RESTORE_REQUEST, "true");
			MvcResult result = mockMvc.perform(request).andReturn();
			assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(transport == 0 ? "/login?error" : null);
			assertThat(result.getResponse().getHeader(HtmxHeaders.REDIRECT)).isEqualTo(transport == 0 ? null : "/login?error");
			assertThat(result.getResponse().getStatus()).isEqualTo(transport == 0 ? 303 : transport == 1 ? 204 : 409);
			assertThat(result.getResponse().getContentAsString()).isEmpty();
			assertThat(result.getResponse().getHeader("Retry-After")).isNull();
		}
	}

	@Test
	void normalizesIdentityButPartitionsBucketsByServletPeerAndIgnoresForwardingInputs() throws Exception {
		postFailure("  CUSTOMER@EXAMPLE.TEST ", "192.0.2.10", null, null, null);
		postFailure("customer@example.test", "192.0.2.10", "198.51.100.7", "203.0.113.8", "198.51.100.9");
		postFailure("customer@example.test", "192.0.2.11", "198.51.100.7", null, "198.51.100.9");

		assertThat(throttle.state(LoginAttemptKey.of("customer@example.test", "192.0.2.10")).failedAttempts()).isEqualTo(2);
		assertThat(throttle.state(LoginAttemptKey.of("customer@example.test", "192.0.2.11")).failedAttempts()).isOne();
	}

	@Test
	void successfulCompletionResetsOnlyTheMatchingSourceBucket() throws Exception {
		postFailure("customer@example.test", "192.0.2.10", null, null, null);
		postFailure("customer@example.test", "192.0.2.11", null, null, null);

		mockMvc.perform(post("/login").with(csrf()).param("email", "customer@example.test")
				.param("password", "CorrectHorseBattery12")
				.with(request -> { request.setRemoteAddr("192.0.2.10"); return request; }))
			.andExpect(status().isSeeOther()).andExpect(redirectedUrl("/"));

		assertThat(throttle.state(LoginAttemptKey.of("customer@example.test", "192.0.2.10")).failedAttempts()).isZero();
		assertThat(throttle.state(LoginAttemptKey.of("customer@example.test", "192.0.2.11")).failedAttempts()).isOne();
	}

	@Test
	void differentIdentitiesAtOnePeerRemainIndependentThroughBlockingAndSuccess() throws Exception {
		String peer = "192.0.2.10";
		LoginAttemptKey customerKey = LoginAttemptKey.of("customer@example.test", peer);
		LoginAttemptKey employeeKey = LoginAttemptKey.of("employee@example.test", peer);
		for (int attempt = 0; attempt < 5; attempt++) postFailure("customer@example.test", peer, null, null, null);
		LoginThrottleState blockedCustomer = throttle.state(customerKey);
		postFailure("employee@example.test", peer, null, null, null);
		postFailure("  EMPLOYEE@EXAMPLE.TEST ", peer, null, null, null);
		assertThat(throttle.state(employeeKey).failedAttempts()).isEqualTo(2);
		assertThat(throttle.state(employeeKey).blockedUntil()).isNull();
		assertThrottleStateUnchanged(customerKey, blockedCustomer);

		mockMvc.perform(post("/login").with(csrf()).param("email", "employee@example.test")
				.param("password", "CorrectHorseBattery12")
				.with(request -> { request.setRemoteAddr(peer); return request; }))
			.andExpect(status().isSeeOther()).andExpect(redirectedUrl("/")).andExpect(authenticated());
		assertThat(throttle.state(employeeKey).failedAttempts()).isZero();
		assertThrottleStateUnchanged(customerKey, blockedCustomer);
		assertThat(accounts.credentialsLookups()).isEqualTo(8);
		assertThat(accounts.successfulLoginWrites()).isOne();
		assertThat(accounts.lastLoginAt(InMemoryAuthenticationAccountStore.EMPLOYEE_ID)).isEqualTo(clock.instant());
		assertThat(accounts.lastLoginAt(InMemoryAuthenticationAccountStore.CUSTOMER_ID)).isNull();
	}

	@Test
	void loginAndProtectedGetsAndLogoutDoNotChangeLoginState() throws Exception {
		postFailure("customer@example.test", "127.0.0.1", null, null, null);
		LoginAttemptKey key = LoginAttemptKey.of("customer@example.test", "127.0.0.1");
		LoginThrottleState failed = throttle.state(key);
		int lookups = accounts.credentialsLookups();
		int locks = throttle.lockCalls();
		mockMvc.perform(get("/login")).andExpect(status().isOk()).andExpect(unauthenticated());
		assertLoginStateUnchanged(key, failed, lookups, locks, 0, null);
		mockMvc.perform(get("/test/security/authenticated"))
			.andExpect(status().isFound()).andExpect(redirectedUrl("/login")).andExpect(unauthenticated());
		assertLoginStateUnchanged(key, failed, lookups, locks, 0, null);

		// Keep a second nonempty bucket so incidental resets cannot hide behind count zero.
		postFailure("customer@example.test", "192.0.2.11", null, null, null);
		LoginAttemptKey otherKey = LoginAttemptKey.of("customer@example.test", "192.0.2.11");
		LoginThrottleState other = throttle.state(otherKey);
		MvcResult login = mockMvc.perform(post("/login").with(csrf()).param("email", "customer@example.test")
				.param("password", "CorrectHorseBattery12"))
			.andExpect(status().isSeeOther()).andExpect(authenticated()).andReturn();
		MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
		LoginThrottleState completed = throttle.state(key);
		Instant lastLogin = accounts.lastLoginAt(InMemoryAuthenticationAccountStore.CUSTOMER_ID);
		assertThat(lastLogin).isEqualTo(clock.instant());
		assertThat(accounts.successfulLoginWrites()).isOne();
		lookups = accounts.credentialsLookups();
		locks = throttle.lockCalls();
		clock.advance(Duration.ofMinutes(1));

		for (int transport = 0; transport < 3; transport++) {
			MockHttpServletRequestBuilder loginGet = get("/login").session(session);
			MockHttpServletRequestBuilder protectedGet = get("/test/security/authenticated").session(session);
			if (transport == 1) {
				loginGet.header(HtmxHeaders.REQUEST, "true");
				protectedGet.header(HtmxHeaders.REQUEST, "true");
			}
			if (transport == 2) {
				loginGet.header(HtmxHeaders.HISTORY_RESTORE_REQUEST, "true");
				protectedGet.header(HtmxHeaders.HISTORY_RESTORE_REQUEST, "true");
			}
			MvcResult redirected = mockMvc.perform(loginGet).andExpect(authenticated()).andReturn();
			assertThat(redirected.getResponse().getStatus()).isEqualTo(transport == 0 ? 303 : transport == 1 ? 204 : 409);
			assertThat(redirected.getResponse().getHeader(transport == 0 ? HttpHeaders.LOCATION : HtmxHeaders.REDIRECT)).isEqualTo("/");
			assertLoginStateUnchanged(key, completed, lookups, locks, 1, lastLogin);
			assertThrottleStateUnchanged(otherKey, other);
			mockMvc.perform(protectedGet).andExpect(status().isOk()).andExpect(content().string("ok")).andExpect(authenticated());
			assertLoginStateUnchanged(key, completed, lookups, locks, 1, lastLogin);
			assertThrottleStateUnchanged(otherKey, other);
		}

		mockMvc.perform(post("/logout").session(session).with(csrf()))
			.andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?logout")).andExpect(unauthenticated());
		assertThat(session.isInvalid()).isTrue();
		assertLoginStateUnchanged(key, completed, lookups, locks, 1, lastLogin);
		assertThrottleStateUnchanged(otherKey, other);
		mockMvc.perform(get("/test/security/authenticated"))
			.andExpect(status().isFound()).andExpect(redirectedUrl("/login")).andExpect(unauthenticated());
		assertLoginStateUnchanged(key, completed, lookups, locks, 1, lastLogin);
	}

	private void assertThrottleStateUnchanged(LoginAttemptKey key, LoginThrottleState expected) {
		assertThat(throttle.state(key)).usingRecursiveComparison().isEqualTo(expected);
	}

	private void assertLoginStateUnchanged(LoginAttemptKey key, LoginThrottleState expected, int lookups,
			int locks, int writes, Instant lastLogin) {
		assertThrottleStateUnchanged(key, expected);
		assertThat(accounts.credentialsLookups()).isEqualTo(lookups);
		assertThat(throttle.lockCalls()).isEqualTo(locks);
		assertThat(accounts.successfulLoginWrites()).isEqualTo(writes);
		assertThat(accounts.lastLoginAt(InMemoryAuthenticationAccountStore.CUSTOMER_ID)).isEqualTo(lastLogin);
	}

	private void postFailure(String email, String peer, String forwarded, String xForwarded, String sourceParameter)
			throws Exception {
		MockHttpServletRequestBuilder request = post("/login").with(csrf()).param("email", email)
				.param("password", "wrong-password").with(servletRequest -> {
					servletRequest.setRemoteAddr(peer);
					return servletRequest;
				});
		if (forwarded != null) request.header("Forwarded", "for=" + forwarded);
		if (xForwarded != null) request.header("X-Forwarded-For", xForwarded);
		if (sourceParameter != null) request.param("source", sourceParameter);
		mockMvc.perform(request).andExpect(status().isSeeOther()).andExpect(redirectedUrl("/login?error"));
	}

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
