package io.github.kubaj12.online_store.identityaccess.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

import io.github.kubaj12.online_store.shared.web.request.BrowserResponse;
import io.github.kubaj12.online_store.shared.web.request.HtmxRequest;

public final class BrowserLogoutSuccessHandler implements LogoutSuccessHandler {
	@Override
	public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
			org.springframework.security.core.Authentication authentication) throws IOException {
		if (!BrowserResponse.redirectHtmx(HtmxRequest.from(request), response, "/login?logout")) {
			response.setStatus(HttpServletResponse.SC_SEE_OTHER);
			response.setHeader("Location", response.encodeRedirectURL(request.getContextPath() + "/login?logout"));
		}
	}
}
