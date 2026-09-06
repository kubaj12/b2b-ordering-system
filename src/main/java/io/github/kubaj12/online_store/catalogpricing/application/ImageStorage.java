package io.github.kubaj12.online_store.catalogpricing.application;

import java.util.Optional;

/** Outbound catalog port for storing image binaries behind opaque references. */
public interface ImageStorage {

	ImageReference store(ImageContent content);

	Optional<ImageContent> load(ImageReference reference);

	void delete(ImageReference reference);

}
