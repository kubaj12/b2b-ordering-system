package io.github.kubaj12.online_store.shared.web.request;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class HtmxRequestTests {

	@Test
	void selectsFragmentsOnlyForOrdinaryHtmxRequests() {
		var ordinaryRequest = new MockHttpServletRequest();
		var htmxRequest = new MockHttpServletRequest();
		htmxRequest.addHeader(HtmxHeaders.REQUEST, "TRUE");
		var historyRequest = new MockHttpServletRequest();
		historyRequest.addHeader(HtmxHeaders.REQUEST, "true");
		historyRequest.addHeader(HtmxHeaders.HISTORY_RESTORE_REQUEST, "true");

		assertThat(HtmxRequest.from(ordinaryRequest).rendersFragment()).isFalse();
		assertThat(HtmxRequest.from(htmxRequest).rendersFragment()).isTrue();
		assertThat(HtmxRequest.from(historyRequest).rendersFragment()).isFalse();
		assertThat(HtmxRequest.from(historyRequest).htmxTransport()).isTrue();
	}

	@Test
	void recognizesTheHistoryLoaderWithoutAnHxRequestHeader() {
		var historyRequest = new MockHttpServletRequest();
		historyRequest.addHeader(HtmxHeaders.HISTORY_RESTORE_REQUEST, "true");

		HtmxRequest request = HtmxRequest.from(historyRequest);

		assertThat(request.htmx()).isFalse();
		assertThat(request.historyRestore()).isTrue();
		assertThat(request.htmxTransport()).isTrue();
	}

	@Test
	void retainsTheServletContextForBrowserRedirects() {
		var servletRequest = new MockHttpServletRequest();
		servletRequest.setContextPath("/zamowienia");

		assertThat(HtmxRequest.from(servletRequest).contextPath()).isEqualTo("/zamowienia");
	}

}
