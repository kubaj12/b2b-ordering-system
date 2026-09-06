package io.github.kubaj12.online_store.catalogpricing.application;

import java.util.Objects;
import java.util.regex.Pattern;

/** Opaque storage identifier. It is intentionally not a user-controlled filesystem path. */
public record ImageReference(String value) {

	private static final Pattern SAFE_REFERENCE = Pattern.compile("^[A-Za-z0-9_-]{1,160}$");

	public ImageReference {
		Objects.requireNonNull(value, "value must not be null");
		if (!SAFE_REFERENCE.matcher(value).matches()) {
			throw new IllegalArgumentException("image reference has an invalid format");
		}
	}

}
