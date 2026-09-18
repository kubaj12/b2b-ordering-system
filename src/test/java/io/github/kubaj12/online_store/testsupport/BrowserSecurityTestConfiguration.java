package io.github.kubaj12.online_store.testsupport;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.identityaccess.application.AccountAccessService;
import io.github.kubaj12.online_store.identityaccess.application.AccountUserDetailsService;
import io.github.kubaj12.online_store.identityaccess.application.LoginAttemptService;
import io.github.kubaj12.online_store.identityaccess.domain.LoginThrottlePolicy;
import io.github.kubaj12.online_store.identityaccess.web.LoginController;

@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServerProperties.class)
@Import({AccountUserDetailsService.class, AccountAccessService.class, LoginAttemptService.class, LoginController.class})
public class BrowserSecurityTestConfiguration {

	@Bean
	PasswordEncoder browserTestPasswordEncoder() {
		return new BCryptPasswordEncoder(4);
	}

	@Bean
	InMemoryAuthenticationAccountStore inMemoryAuthenticationAccountStore(PasswordEncoder encoder) {
		return new InMemoryAuthenticationAccountStore(encoder);
	}

	@Bean
	TestClock browserTestClock() { return new TestClock(); }

	@Bean
	InMemoryLoginThrottleStore inMemoryLoginThrottleStore() { return new InMemoryLoginThrottleStore(); }

	@Bean
	LoginThrottlePolicy browserLoginThrottlePolicy() {
		return new LoginThrottlePolicy(5, java.time.Duration.ofMinutes(15), java.time.Duration.ofMinutes(15),
				java.time.Duration.ofHours(24));
	}

	@Bean
	PlatformTransactionManager browserTransactionManager() {
		return new PlatformTransactionManager() {
			@Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
			@Override public void commit(TransactionStatus status) { }
			@Override public void rollback(TransactionStatus status) { }
		};
	}

	@Bean
	TransactionTemplate browserTransactionTemplate(PlatformTransactionManager manager) {
		return new TransactionTemplate(manager);
	}
}
