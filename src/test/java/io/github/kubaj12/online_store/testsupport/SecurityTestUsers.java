package io.github.kubaj12.online_store.testsupport;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/** Stable browser principals for role-matrix MVC tests. */
public final class SecurityTestUsers {

	private SecurityTestUsers() {
	}

	public static RequestPostProcessor customer() {
		return user("customer@example.test").roles("CUSTOMER");
	}

	public static RequestPostProcessor employee() {
		return user("employee@example.test").roles("EMPLOYEE");
	}

	public static RequestPostProcessor administrator() {
		return user("admin@example.test").roles("ADMIN");
	}

}
