package io.github.kubaj12.online_store.testsupport;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import io.github.kubaj12.online_store.identityaccess.application.AccountAccessService;
import io.github.kubaj12.online_store.identityaccess.application.AccountUserDetailsService;
import io.github.kubaj12.online_store.identityaccess.web.LoginController;

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServerProperties.class)
@Import({AccountUserDetailsService.class, AccountAccessService.class, LoginController.class})
public class BrowserSecurityTestConfiguration {

	@Bean
	PasswordEncoder browserTestPasswordEncoder() {
		return new BCryptPasswordEncoder(4);
	}

	@Bean
	InMemoryAuthenticationAccountStore inMemoryAuthenticationAccountStore(PasswordEncoder encoder) {
		return new InMemoryAuthenticationAccountStore(encoder);
	}
}
