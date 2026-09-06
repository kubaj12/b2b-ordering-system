package io.github.kubaj12.online_store.shared.web.request;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Rendering information derived from HTMX request headers.
 *
 * <p>This value selects an HTML representation only. It must never be used to make an
 * authentication, authorization, or validation decision.</p>
 */
public record HtmxRequest(boolean htmx, boolean historyRestore, String contextPath) {

	public HtmxRequest {
		if (contextPath == null) {
			throw new IllegalArgumentException("context path must not be null");
		}
	}

	public static HtmxRequest from(HttpServletRequest request) {
		return new HtmxRequest(
				headerIsTrue(request, HtmxHeaders.REQUEST),
				headerIsTrue(request, HtmxHeaders.HISTORY_RESTORE_REQUEST),
				request.getContextPath()
		);
	}

	public boolean rendersFragment() {
		return htmx && !historyRestore;
	}

	public boolean htmxTransport() {
		return htmx || historyRestore;
	}

	private static boolean headerIsTrue(HttpServletRequest request, String headerName) {
		return "true".equalsIgnoreCase(request.getHeader(headerName));
	}

}
