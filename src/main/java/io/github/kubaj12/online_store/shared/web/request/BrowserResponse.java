package io.github.kubaj12.online_store.shared.web.request;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.ModelAndView;

/** Builds full-page and fragment variants of the same server-rendered HTML response. */
public final class BrowserResponse {

	private BrowserResponse() {
	}

	public static ModelAndView render(
			HtmxRequest request,
			HttpServletResponse response,
			String pageView,
			String fragmentView,
			Map<String, ?> model
	) {
		return render(request, response, pageView, fragmentView, model, HttpStatus.OK);
	}

	public static ModelAndView render(
			HtmxRequest request,
			HttpServletResponse response,
			String pageView,
			String fragmentView,
			Map<String, ?> model,
			HttpStatusCode status
	) {
		Objects.requireNonNull(request, "HTMX request context must not be null");
		Objects.requireNonNull(response, "HTTP response must not be null");
		Objects.requireNonNull(pageView, "page view must not be null");
		Objects.requireNonNull(fragmentView, "fragment view must not be null");
		Objects.requireNonNull(model, "view model must not be null");
		Objects.requireNonNull(status, "response status must not be null");

		addRepresentationVaryHeaders(response);
		if (request.rendersFragment() && status.isError()) {
			response.setHeader(HtmxHeaders.HANDLED_ERROR, "true");
		}

		String viewName = request.rendersFragment() ? fragmentView : pageView;
		return new ModelAndView(viewName, model, status);
	}

	/**
	 * Redirects an ordinary form through PRG and performs a full navigation for an HTMX request.
	 */
	@Nullable
	public static ModelAndView redirect(
			HtmxRequest request,
			HttpServletResponse response,
			String contextRelativePath
	) {
		if (redirectHtmx(request, response, contextRelativePath)) {
			return null;
		}

		return new ModelAndView("redirect:" + contextRelativePath, HttpStatus.SEE_OTHER);
	}

	/**
	 * Performs a full browser navigation for any HTMX transport request.
	 *
	 * @return whether an HTMX redirect was written
	 */
	public static boolean redirectHtmx(
			HtmxRequest request,
			HttpServletResponse response,
			String contextRelativePath
	) {
		Objects.requireNonNull(request, "HTMX request context must not be null");
		HttpStatus status = request.historyRestore() ? HttpStatus.CONFLICT : HttpStatus.NO_CONTENT;
		return redirectHtmx(request, response, contextRelativePath, status);
	}

	/**
	 * Performs an HTMX full-navigation redirect with an explicit transport status.
	 * History-restoration redirects require an error status so HTMX emits its history-load error
	 * event instead of swapping an empty response.
	 *
	 * @return whether an HTMX redirect was written
	 */
	public static boolean redirectHtmx(
			HtmxRequest request,
			HttpServletResponse response,
			String contextRelativePath,
			HttpStatusCode status
	) {
		Objects.requireNonNull(request, "HTMX request context must not be null");
		Objects.requireNonNull(response, "HTTP response must not be null");
		Objects.requireNonNull(status, "redirect status must not be null");
		validateRedirectPath(contextRelativePath);
		if (request.historyRestore() && !status.isError()) {
			throw new IllegalArgumentException(
					"history-restoration redirects require an error status to prevent an empty swap"
			);
		}

		addRepresentationVaryHeaders(response);
		if (!request.htmxTransport()) {
			return false;
		}

		response.setStatus(status.value());
		response.setHeader(HtmxHeaders.REDIRECT, response.encodeRedirectURL(
				request.contextPath() + contextRelativePath
		));
		return true;
	}

	public static void retargetMainContent(HttpServletResponse response) {
		response.setHeader(HtmxHeaders.RETARGET, "#main-content");
		response.setHeader(HtmxHeaders.RESWAP, "innerHTML");
	}

	private static void validateRedirectPath(String contextRelativePath) {
		if (contextRelativePath == null || !contextRelativePath.startsWith("/")) {
			throw new IllegalArgumentException("redirect path must be context-relative and start with '/'");
		}
		try {
			URI target = new URI(contextRelativePath);
			if (target.isAbsolute() || target.getRawAuthority() != null) {
				throw new IllegalArgumentException("redirect path must not select an external authority");
			}
		}
		catch (URISyntaxException exception) {
			throw new IllegalArgumentException("redirect path must be a valid URI reference", exception);
		}
	}

	private static void addRepresentationVaryHeaders(HttpServletResponse response) {
		Set<String> values = new LinkedHashSet<>();
		for (String header : response.getHeaders(HttpHeaders.VARY)) {
			for (String value : header.split(",")) {
				if (!value.isBlank()) {
					values.add(value.trim());
				}
			}
		}
		values.add(HtmxHeaders.REQUEST);
		values.add(HtmxHeaders.HISTORY_RESTORE_REQUEST);
		response.setHeader(HttpHeaders.VARY, String.join(", ", values));
	}

}
