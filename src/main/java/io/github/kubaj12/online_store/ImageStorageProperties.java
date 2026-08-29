package io.github.kubaj12.online_store;

import java.nio.file.Path;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.image-storage")
record ImageStorageProperties(@NotNull Path root) {

	@AssertTrue(message = "app.image-storage.root must not be empty")
	public boolean isRootConfigured() {
		return root != null && !root.toString().isBlank();
	}

}
