package io.github.kubaj12.online_store.shared.web.error;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Renders Spring Security authorization and CSRF failures through the shared HTML error contract. */
public final class BrowserAccessDeniedHandler implements AccessDeniedHandler {

	private static final AuthenticationTrustResolver AUTHENTICATION_TRUST_RESOLVER =
			new AuthenticationTrustResolverImpl();

	private final LocalizedWebExceptionHandler exceptionHandler;
	private final LocaleResolver localeResolver;
	private final ThymeleafViewResolver viewResolver;
	private final AuthenticationEntryPoint authenticationEntryPoint;

	public BrowserAccessDeniedHandler(
			LocalizedWebExceptionHandler exceptionHandler,
			LocaleResolver localeResolver,
			ThymeleafViewResolver viewResolver,
			AuthenticationEntryPoint authenticationEntryPoint
	) {
		this.exceptionHandler = exceptionHandler;
		this.localeResolver = localeResolver;
		this.viewResolver = viewResolver;
		this.authenticationEntryPoint = authenticationEntryPoint;
	}

	@Override
	public void handle(
			HttpServletRequest request,
			HttpServletResponse response,
			AccessDeniedException accessDeniedException
	) throws IOException, ServletException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null
				|| !authentication.isAuthenticated()
				|| AUTHENTICATION_TRUST_RESOLVER.isAnonymous(authentication)) {
			authenticationEntryPoint.commence(
					request,
					response,
					new InsufficientAuthenticationException(
							"Full authentication is required to access this resource",
							accessDeniedException
					)
			);
			return;
		}

		Locale locale = localeResolver.resolveLocale(request);
		ModelAndView modelAndView = exceptionHandler.forbiddenView(locale, request, response);
		if (modelAndView.getStatus() != null) {
			response.setStatus(modelAndView.getStatus().value());
		}

		String viewName = modelAndView.getViewName();
		if (viewName == null) {
			throw new ServletException("Forbidden response did not select an HTML view");
		}
		try {
			View view = viewResolver.resolveViewName(viewName, locale);
			if (view == null) {
				throw new ServletException("Could not resolve forbidden HTML view");
			}
			view.render(modelAndView.getModel(), request, response);
		}
		catch (IOException | ServletException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new ServletException("Could not render forbidden HTML view", exception);
		}
	}

}
