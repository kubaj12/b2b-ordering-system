package io.github.kubaj12.online_store.testsupport;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/** Stable browser principals for role-matrix MVC tests. */
public final class SecurityTestUsers {

	private SecurityTestUsers() {
	}

	public static RequestPostProcessor customer() {
		return user(principal(InMemoryAuthenticationAccountStore.CUSTOMER_ID, "customer@example.test", "CUSTOMER"));
	}

	public static RequestPostProcessor employee() {
		return user(principal(InMemoryAuthenticationAccountStore.EMPLOYEE_ID, "employee@example.test", "EMPLOYEE"));
	}

	public static RequestPostProcessor administrator() {
		return user(principal(InMemoryAuthenticationAccountStore.ADMIN_ID, "admin@example.test", "ADMIN"));
	}

	public static AccountPrincipal principal(java.util.UUID id, String email, String role) {
		return new AccountPrincipal(id, email, null, role, "ACTIVE", 0);
	}

}
