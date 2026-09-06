package io.github.kubaj12.online_store.testsupport;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.github.kubaj12.online_store.catalogpricing.application.ImageContent;
import io.github.kubaj12.online_store.catalogpricing.application.ImageReference;
import io.github.kubaj12.online_store.catalogpricing.application.ImageStorage;

/** Thread-safe image-storage fake with deterministic references and no filesystem access. */
public final class InMemoryImageStorage implements ImageStorage {

	private final Map<ImageReference, ImageContent> images = new LinkedHashMap<>();
	private final ArrayDeque<RuntimeException> storeFailures = new ArrayDeque<>();
	private final ArrayDeque<RuntimeException> loadFailures = new ArrayDeque<>();
	private final ArrayDeque<RuntimeException> deleteFailures = new ArrayDeque<>();
	private int nextReference = 1;

	@Override
	public synchronized ImageReference store(ImageContent content) {
		Objects.requireNonNull(content, "content must not be null");
		throwIfScripted(storeFailures);
		ImageReference reference;
		do {
			reference = new ImageReference("image-%06d".formatted(nextReference++));
		}
		while (images.containsKey(reference));
		images.put(reference, copy(content));
		return reference;
	}

	@Override
	public synchronized Optional<ImageContent> load(ImageReference reference) {
		Objects.requireNonNull(reference, "reference must not be null");
		throwIfScripted(loadFailures);
		return Optional.ofNullable(images.get(reference)).map(InMemoryImageStorage::copy);
	}

	@Override
	public synchronized void delete(ImageReference reference) {
		Objects.requireNonNull(reference, "reference must not be null");
		throwIfScripted(deleteFailures);
		images.remove(reference);
	}

	public synchronized void seed(ImageReference reference, ImageContent content) {
		images.put(
				Objects.requireNonNull(reference, "reference must not be null"),
				copy(Objects.requireNonNull(content, "content must not be null"))
		);
	}

	public synchronized Map<ImageReference, ImageContent> storedImages() {
		var copy = new LinkedHashMap<ImageReference, ImageContent>();
		images.forEach((reference, content) -> copy.put(reference, copy(content)));
		return Map.copyOf(copy);
	}

	public synchronized void failNextStore(RuntimeException failure) {
		storeFailures.addLast(Objects.requireNonNull(failure, "failure must not be null"));
	}

	public synchronized void failNextLoad(RuntimeException failure) {
		loadFailures.addLast(Objects.requireNonNull(failure, "failure must not be null"));
	}

	public synchronized void failNextDelete(RuntimeException failure) {
		deleteFailures.addLast(Objects.requireNonNull(failure, "failure must not be null"));
	}

	public synchronized void reset() {
		images.clear();
		storeFailures.clear();
		loadFailures.clear();
		deleteFailures.clear();
		nextReference = 1;
	}

	private static void throwIfScripted(ArrayDeque<RuntimeException> failures) {
		RuntimeException failure = failures.pollFirst();
		if (failure != null) {
			throw failure;
		}
	}

	private static ImageContent copy(ImageContent content) {
		return new ImageContent(content.contentType(), content.bytes());
	}

}
