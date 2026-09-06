package io.github.kubaj12.online_store.shared.web.request;

/** Header names used at the private browser/HTMX boundary. */
public final class HtmxHeaders {

	public static final String REQUEST = "HX-Request";
	public static final String HISTORY_RESTORE_REQUEST = "HX-History-Restore-Request";
	public static final String REDIRECT = "HX-Redirect";
	public static final String RETARGET = "HX-Retarget";
	public static final String RESWAP = "HX-Reswap";
	public static final String HANDLED_ERROR = "X-B2B-Handled-Error";

	private HtmxHeaders() {
	}

}
