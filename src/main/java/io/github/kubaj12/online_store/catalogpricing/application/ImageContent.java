package io.github.kubaj12.online_store.catalogpricing.application;

import java.util.Arrays;
import java.util.Objects;

/** Immutable binary content passed across the catalog image-storage port. */
public final class ImageContent {

	private final String contentType;
	private final byte[] bytes;

	public ImageContent(String contentType, byte[] bytes) {
		this.contentType = requireText(contentType);
		this.bytes = Objects.requireNonNull(bytes, "bytes must not be null").clone();
		if (this.bytes.length == 0) {
			throw new IllegalArgumentException("image bytes must not be empty");
		}
	}

	public String contentType() {
		return contentType;
	}

	public byte[] bytes() {
		return bytes.clone();
	}

	public int size() {
		return bytes.length;
	}

	@Override
	public boolean equals(Object candidate) {
		return candidate instanceof ImageContent other
				&& contentType.equals(other.contentType)
				&& Arrays.equals(bytes, other.bytes);
	}

	@Override
	public int hashCode() {
		return 31 * contentType.hashCode() + Arrays.hashCode(bytes);
	}

	@Override
	public String toString() {
		return "ImageContent[contentType=%s, size=%d]".formatted(contentType, bytes.length);
	}

	private static String requireText(String value) {
		Objects.requireNonNull(value, "contentType must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException("contentType must not be blank");
		}
		return value;
	}

}
