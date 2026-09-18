package io.github.kubaj12.online_store.identityaccess.web;

import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import io.github.kubaj12.online_store.identityaccess.application.*;

/** Staff recovery entry point; role-specific target authorization remains in InvitationService. */
@Controller
@PreAuthorize("hasAnyRole('ADMIN', 'EMPLOYEE')")
public class InvitationResendController {
    private final InvitationService service;
    public InvitationResendController(InvitationService service) { this.service = service; }
    @GetMapping("/invitations/resend")
    public String form() { return "identityaccess/resend-invitation"; }
    @PostMapping("/invitations/resend")
    public String resend(@RequestParam String invitationId, @AuthenticationPrincipal AccountPrincipal actor, Model model) {
        try { model.addAttribute("invitationId", service.resend(UUID.fromString(invitationId), actor.accountId()).id()); }
        catch (InvitationException | IllegalArgumentException ignored) { }
        model.addAttribute("requested", true);
        return "identityaccess/resend-invitation";
    }
}
