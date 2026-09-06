package io.github.kubaj12.online_store.identityaccess.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.autoconfigure.web.servlet.PathRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.LocaleResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

import io.github.kubaj12.online_store.shared.web.error.BrowserAccessDeniedHandler;
import io.github.kubaj12.online_store.shared.web.error.LocalizedWebExceptionHandler;
import io.github.kubaj12.online_store.shared.web.request.HtmxRequest;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class BrowserSecurityConfiguration {

	private static final String LOGIN_PATH = "/login";
	private static final String[] ANONYMOUS_PAGE_MATCHERS = {
		LOGIN_PATH,
		"/invitations/accept",
		"/invitations/accept/**",
		"/password-reset",
		"/password-reset/**",
		"/error",
		"/error/**"
	};

	@Bean
	BrowserAuthenticationEntryPoint browserAuthenticationEntryPoint() {
		return new BrowserAuthenticationEntryPoint(LOGIN_PATH);
	}

	@Bean
	BrowserAccessDeniedHandler browserAccessDeniedHandler(
			LocalizedWebExceptionHandler exceptionHandler,
			LocaleResolver localeResolver,
			ThymeleafViewResolver viewResolver,
			BrowserAuthenticationEntryPoint authenticationEntryPoint
	) {
		return new BrowserAccessDeniedHandler(
				exceptionHandler,
				localeResolver,
				viewResolver,
				authenticationEntryPoint
		);
	}

	@Bean
	SecurityFilterChain browserSecurityFilterChain(
			HttpSecurity http,
			BrowserAccessDeniedHandler accessDeniedHandler,
			BrowserAuthenticationEntryPoint authenticationEntryPoint
	) throws Exception {
		http.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(PathRequest.toStaticResources().atCommonLocations()).permitAll()
				.requestMatchers(ANONYMOUS_PAGE_MATCHERS).permitAll()
				.anyRequest().authenticated()
		);
		http.formLogin(Customizer.withDefaults());
		http.exceptionHandling(exceptions -> exceptions
				.defaultAuthenticationEntryPointFor(
						authenticationEntryPoint,
						request -> HtmxRequest.from(request).htmxTransport()
				)
				.accessDeniedHandler(accessDeniedHandler)
		);
		return http.build();
	}

}
