package io.github.kubaj12.online_store.identityaccess.web;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import io.github.kubaj12.online_store.shared.web.request.BrowserResponse;
import io.github.kubaj12.online_store.shared.web.request.HtmxRequest;

/** Starts login without allowing HTMX to swap the login document into a page fragment. */
public final class BrowserAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final String loginPath;
	private final LoginUrlAuthenticationEntryPoint standardEntryPoint;

	public BrowserAuthenticationEntryPoint(String loginPath) {
		this.loginPath = loginPath;
		this.standardEntryPoint = new LoginUrlAuthenticationEntryPoint(loginPath);
	}

	@Override
	public void commence(
			HttpServletRequest request,
			HttpServletResponse response,
			AuthenticationException authenticationException
	) throws IOException, ServletException {
		if (BrowserResponse.redirectHtmx(
				HtmxRequest.from(request),
				response,
				loginPath,
				HttpStatus.UNAUTHORIZED
		)) {
			return;
		}
		standardEntryPoint.commence(request, response, authenticationException);
	}

}
