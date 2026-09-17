package io.github.kubaj12.online_store.identityaccess.web;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.filter.OncePerRequestFilter;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

import io.github.kubaj12.online_store.identityaccess.application.AccountAccessService;
import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;

public final class ActiveAccountRequestFilter extends OncePerRequestFilter {

	private final AccountAccessService accessService;
	private final HandlerExceptionResolver exceptionResolver;
	private final LocaleResolver localeResolver;
	private final ThymeleafViewResolver viewResolver;
	private final RequestMatcher eligibilityBypassMatcher;
	private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

	public ActiveAccountRequestFilter(AccountAccessService accessService,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
			LocaleResolver localeResolver, ThymeleafViewResolver viewResolver,
			RequestMatcher eligibilityBypassMatcher) {
		this.accessService = accessService;
		this.exceptionResolver = exceptionResolver;
		this.localeResolver = localeResolver;
		this.viewResolver = viewResolver;
		this.eligibilityBypassMatcher = eligibilityBypassMatcher;
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() { return false; }

	@Override
	protected boolean shouldNotFilterAsyncDispatch() { return false; }

	@Override
	protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		doFilter(request, response, filterChain);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		doFilter(request, response, filterChain);
	}

	private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (eligibilityBypassMatcher.matches(request)) {
			chain.doFilter(request, response);
			return;
		}
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || authentication instanceof AnonymousAuthenticationToken || !authentication.isAuthenticated()) {
			chain.doFilter(request, response);
			return;
		}
		if (!(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
			invalidate(request, response);
			chain.doFilter(request, response);
			return;
		}
		try {
			if (!accessService.isCurrent(principal)) {
				invalidate(request, response);
			}
		}
		catch (RuntimeException failure) {
			invalidate(request, response);
			renderFailure(request, response, failure);
			return;
		}
		chain.doFilter(request, response);
	}

	private void invalidate(HttpServletRequest request, HttpServletResponse response) {
		logoutHandler.logout(request, response, null);
	}

	private void renderFailure(HttpServletRequest request, HttpServletResponse response, RuntimeException failure)
			throws IOException {
		try {
			ModelAndView modelAndView = exceptionResolver.resolveException(request, response, null, failure);
			if (modelAndView == null || modelAndView.isEmpty()) {
				if (!response.isCommitted()) response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				return;
			}
			if (modelAndView.getStatus() != null) response.setStatus(modelAndView.getStatus().value());
			String viewName = modelAndView.getViewName();
			if (viewName == null) {
				if (!response.isCommitted()) response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				return;
			}
			View view = viewResolver.resolveViewName(viewName, localeResolver.resolveLocale(request));
			if (view == null) throw new IllegalStateException("could not resolve error view");
			view.render(modelAndView.getModel(), request, response);
		}
		catch (Exception renderingFailure) {
			if (!response.isCommitted()) response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}
}
