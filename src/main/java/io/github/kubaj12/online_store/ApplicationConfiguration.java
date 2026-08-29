package io.github.kubaj12.online_store;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
		ApplicationMailProperties.class,
		ApplicationWebProperties.class,
		BootstrapAdminProperties.class,
		ImageStorageProperties.class
})
class ApplicationConfiguration {
}
