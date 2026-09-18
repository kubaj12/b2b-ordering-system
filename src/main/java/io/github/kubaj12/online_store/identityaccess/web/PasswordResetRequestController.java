package io.github.kubaj12.online_store.identityaccess.web;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import io.github.kubaj12.online_store.identityaccess.application.PasswordResetRequestService;
@Controller
public class PasswordResetRequestController {
    private final PasswordResetRequestService service;
    public PasswordResetRequestController(PasswordResetRequestService service) { this.service = service; }
    @GetMapping("/password-reset/{token}")
    public String resetForm(@PathVariable String token, Model model, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("token", token);
        return "identityaccess/reset-password";
    }
    @PostMapping("/password-reset/{token}")
    public String reset(@PathVariable String token, @RequestParam String password, @RequestParam String passwordConfirmation,
            Model model, jakarta.servlet.http.HttpServletResponse response) {
        resetForm(token, model, response);
        try {
            if (password.equals(passwordConfirmation) && service.reset(token, password)) {
                model.addAttribute("completed", true);
                return "identityaccess/reset-password";
            }
        } catch (IllegalArgumentException ignored) { }
        model.addAttribute("resetError", true);
        return "identityaccess/reset-password";
    }
    @GetMapping("/password-reset")
    public String form() { return "identityaccess/request-password-reset"; }
    @PostMapping("/password-reset")
    public String request(@RequestParam String email, Model model) {
        service.request(email);
        model.addAttribute("requested", true);
        return "identityaccess/request-password-reset";
    }
}
