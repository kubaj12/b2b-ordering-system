package io.github.kubaj12.online_store.identityaccess.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.github.kubaj12.online_store.identityaccess.application.InvitationException;
import io.github.kubaj12.online_store.identityaccess.application.InvitationService;
import io.github.kubaj12.online_store.shared.web.error.WebErrorException;

@Controller
public class InvitationAcceptanceController {
    private final InvitationService invitations;
    public InvitationAcceptanceController(InvitationService invitations) { this.invitations = invitations; }
    @GetMapping("/invitations/accept/{token}")
    public String form(@PathVariable String token, Model model, HttpServletResponse response) {
        protectTokenPage(response);
        model.addAttribute("token", token);
        return "identityaccess/accept-invitation";
    }
    @PostMapping("/invitations/accept/{token}")
    public String accept(@PathVariable String token, @RequestParam String password,
            @RequestParam String passwordConfirmation, Model model, HttpServletResponse response) {
        protectTokenPage(response);
        if (!password.equals(passwordConfirmation)) {
            model.addAttribute("token", token);
            model.addAttribute("passwordError", true);
            return "identityaccess/accept-invitation";
        }
        try { invitations.accept(token, password); }
        catch (IllegalArgumentException exception) {
            model.addAttribute("token", token);
            model.addAttribute("passwordError", true);
            return "identityaccess/accept-invitation";
        }
        catch (InvitationException exception) { throw WebErrorException.conflict("identity.invitation.unavailable"); }
        return "identityaccess/invitation-accepted";
    }
    private static void protectTokenPage(HttpServletResponse response) {
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
    }
}
