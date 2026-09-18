package io.github.kubaj12.online_store.identityaccess.web;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;
import io.github.kubaj12.online_store.identityaccess.application.LoginAttemptService;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import io.github.kubaj12.online_store.shared.web.request.BrowserResponse;
import io.github.kubaj12.online_store.shared.web.request.HtmxRequest;

public final class BrowserLoginSuccessHandler implements AuthenticationSuccessHandler {

	private final LoginAttemptService loginAttemptService;
	private final BrowserLoginFailureHandler failureHandler;
	private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

	public BrowserLoginSuccessHandler(LoginAttemptService loginAttemptService, BrowserLoginFailureHandler failureHandler) {
		this.loginAttemptService = loginAttemptService;
		this.failureHandler = failureHandler;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException, ServletException {
		if (!(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
			failure(request, response, authentication);
			return;
		}
		if (!(authentication.getDetails() instanceof WebAuthenticationDetails details)) {
			failure(request, response, authentication);
			return;
		}
		try {
			if (!loginAttemptService.completeSuccessfulLogin(principal, details.getRemoteAddress())) {
				failure(request, response, authentication);
				return;
			}
		} catch (RuntimeException exception) {
			failure(request, response, authentication);
			return;
		}
		if (!BrowserResponse.redirectHtmx(HtmxRequest.from(request), response, "/")) {
			response.setStatus(HttpServletResponse.SC_SEE_OTHER);
			response.setHeader("Location", response.encodeRedirectURL(request.getContextPath() + "/"));
		}
	}

	private void failure(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
			throws IOException, ServletException {
		logoutHandler.logout(request, response, authentication);
		failureHandler.onAuthenticationFailure(request, response,
				new org.springframework.security.authentication.BadCredentialsException("Invalid credentials"));
	}
}
