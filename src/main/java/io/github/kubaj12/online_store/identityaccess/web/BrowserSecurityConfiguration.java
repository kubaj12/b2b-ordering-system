package io.github.kubaj12.online_store.identityaccess.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.LocaleResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

import io.github.kubaj12.online_store.identityaccess.application.AccountAccessService;
import io.github.kubaj12.online_store.identityaccess.application.AccountAuthenticationProvider;
import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;
import io.github.kubaj12.online_store.identityaccess.application.AccountUserDetailsService;
import io.github.kubaj12.online_store.identityaccess.application.LoginAttemptService;
import io.github.kubaj12.online_store.shared.web.error.BrowserAccessDeniedHandler;
import io.github.kubaj12.online_store.shared.web.error.LocalizedWebExceptionHandler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class BrowserSecurityConfiguration {

	private static final String LOGIN_PATH = "/login";

	@Bean
	BrowserAuthenticationEntryPoint browserAuthenticationEntryPoint() { return new BrowserAuthenticationEntryPoint(LOGIN_PATH); }

	@Bean
	BrowserAccessDeniedHandler browserAccessDeniedHandler(LocalizedWebExceptionHandler exceptionHandler,
			LocaleResolver localeResolver, ThymeleafViewResolver viewResolver,
			BrowserAuthenticationEntryPoint authenticationEntryPoint) {
		return new BrowserAccessDeniedHandler(exceptionHandler, localeResolver, viewResolver, authenticationEntryPoint);
	}

	@Bean
	RequestMatcher browserPublicRequestMatcher() {
		RequestMatcher staticResources = PathRequest.toStaticResources().atCommonLocations();
		RequestMatcher login = request -> isGetOrHead(request) || "POST".equals(request.getMethod())
				? methodPathMatches(request, LOGIN_PATH) : false;
		return request -> (staticResources.matches(request) && isGetOrHead(request))
				|| login.matches(request) || tokenPathMatches(request, "/invitations/accept")
				|| tokenPathMatches(request, "/password-reset") || exactError(request);
	}

	@Bean
	RequestMatcher browserEligibilityBypassMatcher() {
		RequestMatcher staticResources = PathRequest.toStaticResources().atCommonLocations();
		return request -> (staticResources.matches(request) && isGetOrHead(request)) || exactError(request);
	}

	@Bean
	AccountAuthenticationProvider accountAuthenticationProvider(AccountUserDetailsService userDetailsService,
			org.springframework.security.crypto.password.PasswordEncoder passwordEncoder, LoginAttemptService loginAttemptService) {
		return new AccountAuthenticationProvider(userDetailsService, passwordEncoder, loginAttemptService);
	}

	@Bean
	SecurityFilterChain browserSecurityFilterChain(HttpSecurity http, BrowserAccessDeniedHandler accessDeniedHandler,
			BrowserAuthenticationEntryPoint authenticationEntryPoint, AccountAccessService accessService,
			@Qualifier("handlerExceptionResolver") org.springframework.web.servlet.HandlerExceptionResolver exceptionResolver,
			LocaleResolver localeResolver, ThymeleafViewResolver viewResolver,
			AccountAuthenticationProvider authenticationProvider, BrowserLoginSuccessHandler loginSuccessHandler,
			BrowserLoginFailureHandler loginFailureHandler, BrowserLogoutSuccessHandler logoutSuccessHandler,
			RequestMatcher browserPublicRequestMatcher, RequestMatcher browserEligibilityBypassMatcher,
			ServerProperties serverProperties, ApplicationEventPublisher applicationEventPublisher) throws Exception {
		ActiveAccountRequestFilter activeAccountRequestFilter = new ActiveAccountRequestFilter(accessService,
				exceptionResolver, localeResolver, viewResolver, browserEligibilityBypassMatcher);
		http.addFilterAfter(activeAccountRequestFilter, HeaderWriterFilter.class);
		ProviderManager authenticationManager = new ProviderManager(authenticationProvider);
		authenticationManager.setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(applicationEventPublisher));
		http.authenticationManager(authenticationManager);
		http.authorizeHttpRequests(authorize -> authorize.requestMatchers(browserPublicRequestMatcher).permitAll()
				.anyRequest().authenticated());
		http.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint)
				.accessDeniedHandler(accessDeniedHandler));
		http.requestCache(cache -> cache.requestCache(new org.springframework.security.web.savedrequest.NullRequestCache()));
		http.formLogin(form -> form.loginPage(LOGIN_PATH).loginProcessingUrl(LOGIN_PATH)
				.usernameParameter("email").passwordParameter("password")
				.successHandler(loginSuccessHandler).failureHandler(loginFailureHandler));
		RequestMatcher logoutRequest = new AndRequestMatcher(methodMatcher(HttpMethod.POST, "/logout"), request -> {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			return authentication != null && authentication.getPrincipal() instanceof AccountPrincipal;
		});
		http.logout(logout -> logout.logoutRequestMatcher(logoutRequest)
				.addLogoutHandler(new CookieClearingLogoutHandler(sessionCookieName(serverProperties)))
				.logoutSuccessHandler(logoutSuccessHandler));
		http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
		return http.build();
	}

	@Bean
	BrowserLoginFailureHandler browserLoginFailureHandler() { return new BrowserLoginFailureHandler(); }

	@Bean
	BrowserLoginSuccessHandler browserLoginSuccessHandler(LoginAttemptService loginAttemptService,
			BrowserLoginFailureHandler failureHandler) { return new BrowserLoginSuccessHandler(loginAttemptService, failureHandler); }

	@Bean
	BrowserLogoutSuccessHandler browserLogoutSuccessHandler() { return new BrowserLogoutSuccessHandler(); }

	private static String sessionCookieName(ServerProperties properties) {
		String name = properties.getServlet().getSession().getCookie().getName();
		return name == null || name.isBlank() ? "JSESSIONID" : name;
	}

	private static boolean methodPathMatches(HttpServletRequest request, String path) {
		return methodMatcher(request.getMethod(), path).matches(request);
	}
	private static RequestMatcher methodMatcher(HttpMethod method, String path) {
		return PathPatternRequestMatcher.withDefaults().matcher(method, path);
	}
	private static RequestMatcher methodMatcher(String method, String path) {
		return methodMatcher(HttpMethod.valueOf(method), path);
	}
	private static boolean tokenPathMatches(HttpServletRequest request, String base) {
		if (!isGetHeadPost(request)) return false;
		String path = pathWithinApplication(request);
		return path.equals(base) || (path.startsWith(base + "/") && path.indexOf('/', base.length() + 1) < 0);
	}
	private static boolean exactError(HttpServletRequest request) {
		return "/error".equals(pathWithinApplication(request))
				&& (request.getDispatcherType() == DispatcherType.ERROR || isGetOrHead(request));
	}
	private static boolean isGetOrHead(HttpServletRequest request) {
		return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
	}
	private static boolean isGetHeadPost(HttpServletRequest request) {
		return isGetOrHead(request) || "POST".equals(request.getMethod());
	}
	private static String pathWithinApplication(HttpServletRequest request) {
		return request.getRequestURI().substring(request.getContextPath().length());
	}
}
