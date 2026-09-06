package io.github.kubaj12.online_store.shared.web.request;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserResponseTests {

	@Test
	void rejectsExternalAndMalformedRedirectTargets() {
		var request = new HtmxRequest(true, false, "");
		var response = new MockHttpServletResponse();

		assertThatThrownBy(() -> BrowserResponse.redirect(request, response, "https://example.test"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> BrowserResponse.redirect(request, response, "//example.test/path"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> BrowserResponse.redirect(request, response, "/safe\r\nInjected: value"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void includesTheServletContextInHtmxRedirects() {
		var request = new HtmxRequest(true, false, "/zamowienia");
		var response = new MockHttpServletResponse();

		BrowserResponse.redirect(request, response, "/koszyk");

		assertThat(response.getStatus()).isEqualTo(204);
		assertThat(response.getHeader(HtmxHeaders.REDIRECT)).isEqualTo("/zamowienia/koszyk");
	}

	@Test
	void usesANonSwappableRedirectDuringHistoryRestoration() {
		var request = new HtmxRequest(true, true, "");
		var response = new MockHttpServletResponse();

		BrowserResponse.redirect(request, response, "/login");

		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(response.getHeader(HtmxHeaders.REDIRECT)).isEqualTo("/login");
	}

	@Test
	void rejectsASwappableStatusForAHistoryRestorationRedirect() {
		var request = new HtmxRequest(true, true, "");
		var response = new MockHttpServletResponse();

		assertThatThrownBy(() -> BrowserResponse.redirectHtmx(
				request,
				response,
				"/login",
				org.springframework.http.HttpStatus.NO_CONTENT
		)).isInstanceOf(IllegalArgumentException.class);
	}

}
