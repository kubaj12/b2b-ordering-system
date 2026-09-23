package io.github.kubaj12.online_store.identityaccess.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;
import io.github.kubaj12.online_store.identityaccess.application.PasswordChangeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Controller
public class PasswordChangeController {
    private final PasswordChangeService service;

    public PasswordChangeController(PasswordChangeService service) { this.service = service; }

    @GetMapping("/account/password")
    public String form(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return "identityaccess/change-password";
    }

    @PostMapping("/account/password")
    public String change(@AuthenticationPrincipal AccountPrincipal principal,
            @RequestParam String currentPassword, @RequestParam String password,
            @RequestParam String passwordConfirmation, Model model,
            HttpServletRequest request, HttpServletResponse response) {
        form(response);
        try {
            if (password.equals(passwordConfirmation)
                    && service.change(principal.accountId(), principal.securityVersion(), currentPassword, password)) {
                new SecurityContextLogoutHandler().logout(request, response, null);
                model.addAttribute("completed", true);
                return "identityaccess/change-password";
            }
        } catch (IllegalArgumentException ignored) { }
        model.addAttribute("passwordError", true);
        return "identityaccess/change-password";
    }
}
