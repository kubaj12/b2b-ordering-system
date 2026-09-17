package io.github.kubaj12.online_store.identityaccess.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import io.github.kubaj12.online_store.identityaccess.application.AccountAccessService;
import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;
import io.github.kubaj12.online_store.shared.web.request.BrowserResponse;
import io.github.kubaj12.online_store.shared.web.request.HtmxRequest;

@Controller
public final class LoginController {

	private final AccountAccessService accessService;

	public LoginController(AccountAccessService accessService) {
		this.accessService = accessService;
	}

	@GetMapping("/login")
	public ModelAndView login(HtmxRequest request, HttpServletResponse response, Authentication authentication,
			@RequestParam(name = "error", required = false) String error,
			@RequestParam(name = "logout", required = false) String logout) {
		if (authentication != null && authentication.isAuthenticated()
				&& authentication.getPrincipal() instanceof AccountPrincipal principal
				&& accessService.isCurrent(principal)) {
			return BrowserResponse.redirect(request, response, "/");
		}
		if (request.htmxTransport()) {
			BrowserResponse.redirectHtmx(request, response, "/login");
			return null;
		}
		return BrowserResponse.render(request, response, "identityaccess/login", "identityaccess/login",
				Map.of("loginError", error != null, "loggedOut", logout != null));
	}
}
