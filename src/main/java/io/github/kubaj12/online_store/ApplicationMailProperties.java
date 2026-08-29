package io.github.kubaj12.online_store;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.mail")
record ApplicationMailProperties(
		boolean deliveryEnabled,
		@NotBlank @Email String fromAddress
) {
}
