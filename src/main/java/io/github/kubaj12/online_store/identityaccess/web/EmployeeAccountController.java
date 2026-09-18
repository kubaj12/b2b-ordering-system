package io.github.kubaj12.online_store.identityaccess.web;

import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import io.github.kubaj12.online_store.identityaccess.application.*;
import io.github.kubaj12.online_store.shared.web.error.WebErrorException;
import io.github.kubaj12.online_store.shared.web.request.*;

@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin/employees")
public class EmployeeAccountController {
    private final EmployeeAccountService accounts;
    private final InvitationService invitations;
    public EmployeeAccountController(EmployeeAccountService accounts, InvitationService invitations) {
        this.accounts = accounts; this.invitations = invitations;
    }
    @GetMapping
    public ModelAndView directory(@AuthenticationPrincipal AccountPrincipal actor, HttpServletRequest request, HttpServletResponse response) {
        return BrowserResponse.render(HtmxRequest.from(request), response, "identityaccess/employees", "identityaccess/employees :: directory",
            Map.of("directory", accounts.directory(actor.accountId())));
    }
    @PostMapping("/invite")
    public ModelAndView invite(@RequestParam String email, @AuthenticationPrincipal AccountPrincipal actor, HttpServletRequest request, HttpServletResponse response) {
        try { invitations.inviteEmployee(email, actor.accountId()); }
        catch (InvitationException exception) { throw WebErrorException.conflict(); }
        catch (IllegalArgumentException exception) { throw WebErrorException.validation(); }
        return redirect(request, response);
    }
    @PostMapping("/{id}/block")
    public ModelAndView block(@PathVariable UUID id, @AuthenticationPrincipal AccountPrincipal actor, HttpServletRequest request, HttpServletResponse response) {
        accounts.block(actor.accountId(), id);
        return redirect(request, response);
    }
    @PostMapping("/{id}/unblock")
    public ModelAndView unblock(@PathVariable UUID id, @AuthenticationPrincipal AccountPrincipal actor, HttpServletRequest request, HttpServletResponse response) {
        accounts.unblock(actor.accountId(), id);
        return redirect(request, response);
    }
    private ModelAndView redirect(HttpServletRequest request, HttpServletResponse response) {
        return BrowserResponse.redirect(HtmxRequest.from(request), response, "/admin/employees");
    }
}
