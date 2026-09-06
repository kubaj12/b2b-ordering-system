package io.github.kubaj12.online_store.shared.web.request;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
class AnonymousBrowserRouteTestController {

	@GetMapping(
			path = {
				"/invitations/accept",
				"/invitations/accept/{token}",
				"/password-reset",
				"/password-reset/{token}"
			},
			produces = MediaType.TEXT_HTML_VALUE
	)
	ModelAndView anonymousPage(HttpServletRequest request) {
		return new ModelAndView(
				"test/anonymous-flow",
				Map.of("anonymousRoute", request.getRequestURI())
		);
	}

}
