package io.github.kubaj12.online_store.identityaccess.web;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@Profile("mvc-security-test")
class SecurityProbeController {

	private final SecurityProbeService service;

	SecurityProbeController(SecurityProbeService service) {
		this.service = service;
	}

	@GetMapping("/test/security/authenticated")
	@ResponseBody
	String authenticated() {
		return service.call();
	}

	@GetMapping("/test/security/customer")
	@ResponseBody
	@PreAuthorize("hasRole('CUSTOMER')")
	String customer() {
		return service.call();
	}

	@GetMapping("/test/security/staff")
	@ResponseBody
	@PreAuthorize("hasAnyRole('EMPLOYEE', 'ADMIN')")
	String staff() {
		return service.call();
	}

	@GetMapping("/test/security/admin")
	@ResponseBody
	@PreAuthorize("hasRole('ADMIN')")
	String administrator() {
		return service.call();
	}

	@PostMapping("/test/security/command")
	@ResponseBody
	String authenticatedCommand() {
		return service.call();
	}

	@GetMapping({"/invitations/accept/{token}", "/password-reset/{token}"})
	@ResponseBody
	String anonymousPage() {
		return "anonymous";
	}

	@PostMapping("/invitations/accept/{token}")
	@ResponseBody
	String anonymousCommand() {
		return service.call();
	}

}
