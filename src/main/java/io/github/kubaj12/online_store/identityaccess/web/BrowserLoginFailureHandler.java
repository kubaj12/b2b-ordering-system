package io.github.kubaj12.online_store.identityaccess.web;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

import io.github.kubaj12.online_store.shared.web.request.BrowserResponse;
import io.github.kubaj12.online_store.shared.web.request.HtmxRequest;

public final class BrowserLoginFailureHandler implements AuthenticationFailureHandler {

	private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		logoutHandler.logout(request, response, null);
		if (!BrowserResponse.redirectHtmx(HtmxRequest.from(request), response, "/login?error")) {
			response.setStatus(HttpServletResponse.SC_SEE_OTHER);
			response.setHeader("Location", response.encodeRedirectURL(request.getContextPath() + "/login?error"));
		}
	}
}
