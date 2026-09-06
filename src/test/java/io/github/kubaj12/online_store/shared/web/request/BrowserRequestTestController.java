package io.github.kubaj12.online_store.shared.web.request;

import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
@RequestMapping(path = "/test/browser-flow", produces = MediaType.TEXT_HTML_VALUE)
class BrowserRequestTestController {

	private static final String PAGE_VIEW = "test/browser-flow";
	private static final String FRAGMENT_VIEW = PAGE_VIEW + " :: flowContent";

	private final BrowserRequestTestApplicationService applicationService;

	BrowserRequestTestController(BrowserRequestTestApplicationService applicationService) {
		this.applicationService = applicationService;
	}

	@GetMapping
	@PreAuthorize("hasRole('EMPLOYEE')")
	ModelAndView show(HtmxRequest request, HttpServletResponse response) {
		return BrowserResponse.render(
				request,
				response,
				PAGE_VIEW,
				FRAGMENT_VIEW,
				Map.of(
						"testForm", new TestForm(""),
						"displayValue", applicationService.load()
				)
		);
	}

	@PostMapping
	@PreAuthorize("hasRole('EMPLOYEE')")
	ModelAndView submit(
			@Valid @ModelAttribute("testForm") TestForm form,
			BindingResult bindingResult,
			HtmxRequest request,
			HttpServletResponse response,
			Model model
	) {
		if (bindingResult.hasErrors()) {
			model.addAttribute("displayValue", "");
			return BrowserResponse.render(
					request,
					response,
					PAGE_VIEW,
					FRAGMENT_VIEW,
					model.asMap(),
					HttpStatus.BAD_REQUEST
			);
		}

		applicationService.submit(form.name());
		return BrowserResponse.redirect(request, response, "/test/browser-flow");
	}

	record TestForm(@NotBlank String name) {
	}

}
