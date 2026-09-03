package io.github.kubaj12.online_store.shared.web.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebErrorExceptionTests {

	@Test
	void exposesOnlySupportedBrowserErrorStatuses() {
		assertThat(WebErrorException.validation().status()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(WebErrorException.forbidden().status()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(WebErrorException.notFound().status()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(WebErrorException.conflict().status()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void defensivelyCopiesLocalizedMessageArguments() {
		Object[] arguments = { "bezpieczny-identyfikator" };
		WebErrorException exception = WebErrorException.conflict("error.409.message", arguments);
		arguments[0] = "zmieniony";

		assertThat(exception.messageArguments()).containsExactly("bezpieczny-identyfikator");
		Object[] returnedArguments = exception.messageArguments();
		returnedArguments[0] = "ponownie-zmieniony";
		assertThat(exception.messageArguments()).containsExactly("bezpieczny-identyfikator");
	}

	@Test
	void rejectsInvalidLocalizationKeysAndNullArgumentArrays() {
		assertThatThrownBy(() -> WebErrorException.notFound("raw text"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("invalid format");
		assertThatThrownBy(() -> WebErrorException.notFound("error.404.message", (Object[]) null))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("arguments");
	}

}
