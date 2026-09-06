package io.github.kubaj12.online_store.testsupport;

import org.junit.jupiter.api.Test;

import io.github.kubaj12.online_store.catalogpricing.application.ImageContent;
import io.github.kubaj12.online_store.catalogpricing.application.ImageReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryImageStorageTests {

	private final InMemoryImageStorage storage = new InMemoryImageStorage();

	@Test
	void storesAndLoadsDefensiveCopiesUnderDeterministicReferences() {
		byte[] suppliedBytes = {1, 2, 3};
		var firstReference = storage.store(new ImageContent("image/png", suppliedBytes));
		var secondReference = storage.store(new ImageContent("image/jpeg", new byte[] {4, 5}));
		suppliedBytes[0] = 99;

		ImageContent loaded = storage.load(firstReference).orElseThrow();
		byte[] returnedBytes = loaded.bytes();
		returnedBytes[1] = 99;

		assertThat(firstReference.value()).isEqualTo("image-000001");
		assertThat(secondReference.value()).isEqualTo("image-000002");
		assertThat(storage.load(firstReference).orElseThrow().bytes()).containsExactly(1, 2, 3);
	}

	@Test
	void deletesStoredImages() {
		var reference = storage.store(new ImageContent("image/png", new byte[] {1}));

		storage.delete(reference);

		assertThat(storage.load(reference)).isEmpty();
	}

	@Test
	void generatedReferencesSkipSeededImagesDuringReplacement() {
		var oldReference = new ImageReference("image-000001");
		var oldContent = new ImageContent("image/png", new byte[] {1});
		var replacementContent = new ImageContent("image/png", new byte[] {2});
		storage.seed(oldReference, oldContent);

		ImageReference replacementReference = storage.store(replacementContent);
		storage.delete(oldReference);

		assertThat(replacementReference.value()).isEqualTo("image-000002");
		assertThat(storage.load(oldReference)).isEmpty();
		assertThat(storage.load(replacementReference)).contains(replacementContent);
	}

	@Test
	void scriptsFailuresWithoutMutatingStorageOrConsumingAReference() {
		storage.failNextStore(new IllegalStateException("test store failure"));

		assertThatThrownBy(() -> storage.store(new ImageContent("image/png", new byte[] {1})))
				.isInstanceOf(IllegalStateException.class);
		var reference = storage.store(new ImageContent("image/png", new byte[] {2}));

		assertThat(reference.value()).isEqualTo("image-000001");
		assertThat(storage.storedImages()).containsOnlyKeys(reference);
	}

	@Test
	void resetClearsStateFailuresAndTheReferenceSequence() {
		storage.store(new ImageContent("image/png", new byte[] {1}));
		storage.failNextLoad(new IllegalStateException("test load failure"));

		storage.reset();
		var reference = storage.store(new ImageContent("image/png", new byte[] {2}));

		assertThat(reference.value()).isEqualTo("image-000001");
		assertThat(storage.load(reference)).isPresent();
	}

}
