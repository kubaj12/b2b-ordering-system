package io.github.kubaj12.online_store.shared.web.error;

import java.util.LinkedHashMap;
import java.util.List;

import jakarta.validation.constraints.Min;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

@Controller
final class SharedWebTestController {

	@GetMapping("/test/layout")
	String layout() {
		return "index";
	}

	@GetMapping("/test/components")
	String components(
			@RequestParam(defaultValue = "4") int page,
			@RequestParam(defaultValue = "240") long total,
			Model model
	) {
		var form = new ExampleForm("");
		var bindingResult = new BeanPropertyBindingResult(form, "exampleForm");
		bindingResult.reject("validation.invalid");
		bindingResult.rejectValue("name", "validation.required");
		model.addAllAttributes(bindingResult.getModel());

		model.addAttribute("flashSuccessMessage", "Zapisano <script>alert('sekret')</script>");
		model.addAttribute("flashInfoMessage", "Dane są aktualne.");
		model.addAttribute("flashWarningMessage", "Sprawdź dostępność.");
		model.addAttribute("flashErrorMessage", "Nie udało się zapisać.");
		model.addAttribute("resultPage", new PageImpl<>(List.of(), PageRequest.of(page, 20), total));

		var retainedParameters = new LinkedHashMap<String, List<String>>();
		retainedParameters.put("query", List.of("śruby & nakrętki"));
		retainedParameters.put("category", List.of("metal"));
		model.addAttribute("retainedParameters", retainedParameters);
		return "test/components";
	}

	@GetMapping("/test/errors/validation")
	String validationError() {
		throw WebErrorException.validation();
	}

	@GetMapping("/test/errors/conflict")
	String conflictError() {
		throw WebErrorException.conflict();
	}

	@GetMapping("/test/errors/not-found")
	String notFoundError() {
		throw WebErrorException.notFound();
	}

	@GetMapping("/test/errors/forbidden")
	String forbiddenError() {
		throw new AccessDeniedException("internal authorization reason");
	}

	@GetMapping("/test/errors/unexpected")
	String unexpectedError() {
		throw new IllegalStateException("tajny-szczegół-techniczny");
	}

	@GetMapping("/test/errors/type-mismatch")
	String typeMismatch(@RequestParam int quantity) {
		return "index";
	}

	@GetMapping("/test/errors/method-validation")
	String methodValidation(@RequestParam @Min(1) int quantity) {
		return "index";
	}

	@GetMapping("/test/errors/return-validation")
	@ResponseBody
	@Min(1)
	int returnValidation() {
		return 0;
	}

	@GetMapping("/test/errors/service-unavailable")
	String serviceUnavailableError() {
		throw new ResponseStatusException(
				HttpStatus.SERVICE_UNAVAILABLE,
				"sensitive upstream detail",
				new IllegalArgumentException("sensitive nested cause")
		);
	}

	record ExampleForm(String name) {
	}

}
