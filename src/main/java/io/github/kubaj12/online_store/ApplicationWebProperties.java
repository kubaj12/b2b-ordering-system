package io.github.kubaj12.online_store;

import java.net.URI;
import java.util.Set;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.StringUtils;

@Validated
@ConfigurationProperties("app.web")
record ApplicationWebProperties(@NotNull URI baseUrl) {

	private static final Set<String> SUPPORTED_SCHEMES = Set.of("http", "https");

	@AssertTrue(message = "app.web.base-url must be an absolute HTTP(S) URL without user info, query, or fragment")
	public boolean isValidBaseUrl() {
		return baseUrl != null
				&& baseUrl.isAbsolute()
				&& SUPPORTED_SCHEMES.contains(baseUrl.getScheme())
				&& StringUtils.hasText(baseUrl.getHost())
				&& baseUrl.getUserInfo() == null
				&& baseUrl.getQuery() == null
				&& baseUrl.getFragment() == null;
	}

}
