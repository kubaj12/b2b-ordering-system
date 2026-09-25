package io.github.kubaj12.online_store.customers.web;

import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import io.github.kubaj12.online_store.customers.application.*;
import io.github.kubaj12.online_store.shared.web.error.WebErrorException;
import io.github.kubaj12.online_store.shared.web.request.*;

@Controller
@PreAuthorize("hasAnyRole('EMPLOYEE','ADMIN')")
@RequestMapping("/staff/customers")
public class CustomerAdministrationController {
    private final CustomerAdministrationService customers;
    public CustomerAdministrationController(CustomerAdministrationService customers) { this.customers = customers; }
    @GetMapping public ModelAndView list(HttpServletRequest req, HttpServletResponse res) {
        return render(req, res, "customers/list", "customers/list :: content", Map.of("directory", customers.directory()));
    }
    @GetMapping("/new") public ModelAndView createForm(HttpServletRequest req, HttpServletResponse res) {
        return render(req, res, "customers/create", "customers/create :: content", Map.of("form", new CustomerInvitationForm()));
    }
    @PostMapping public ModelAndView create(@Valid @ModelAttribute("form") CustomerInvitationForm form,
            BindingResult errors, @AuthenticationPrincipal(expression="accountId") UUID actor,
            HttpServletRequest req, HttpServletResponse res) {
        if (errors.hasErrors()) return render(req, res, "customers/create", "customers/create :: content", Map.of("form", form));
        try { customers.invite(form.email(), form.profile().toData(), actor); }
        catch (CustomerInvitationConflictException | DataIntegrityViolationException exception) { throw WebErrorException.conflict(); }
        catch (IllegalArgumentException exception) { throw WebErrorException.validation(); }
        return BrowserResponse.redirect(HtmxRequest.from(req), res, "/staff/customers");
    }
    @GetMapping("/{id}") public ModelAndView detail(@PathVariable UUID id, HttpServletRequest req, HttpServletResponse res) {
        try { return render(req, res, "customers/detail", "customers/detail :: content", Map.of("customer", customers.detail(id))); }
        catch (CustomerNotFoundException exception) { throw WebErrorException.notFound(); }
    }
    @GetMapping("/{id}/edit") public ModelAndView editForm(@PathVariable UUID id, HttpServletRequest req, HttpServletResponse res) {
        try { var customer = customers.detail(id); return render(req, res, "customers/edit", "customers/edit :: content",
                Map.of("customer", customer, "form", CustomerProfileForm.from(customer.profile()))); }
        catch (CustomerNotFoundException exception) { throw WebErrorException.notFound(); }
    }
    @PostMapping("/{id}") public ModelAndView edit(@PathVariable UUID id,
            @Valid @ModelAttribute("form") CustomerProfileForm form, BindingResult errors,
            HttpServletRequest req, HttpServletResponse res) {
        if (errors.hasErrors()) return render(req, res, "customers/edit", "customers/edit :: content", Map.of("customer", customers.detail(id), "form", form));
        try { customers.update(id, form.toData()); }
        catch (CustomerNotFoundException exception) { throw WebErrorException.notFound(); }
        catch (DataIntegrityViolationException exception) { throw WebErrorException.conflict(); }
        return BrowserResponse.redirect(HtmxRequest.from(req), res, "/staff/customers/" + id);
    }
    @PostMapping("/{id}/block") public ModelAndView block(@PathVariable UUID id,
            @AuthenticationPrincipal(expression="accountId") UUID actor, HttpServletRequest req, HttpServletResponse res) {
        customers.block(actor, id); return detailRedirect(id, req, res);
    }
    @PostMapping("/{id}/unblock") public ModelAndView unblock(@PathVariable UUID id,
            @AuthenticationPrincipal(expression="accountId") UUID actor, HttpServletRequest req, HttpServletResponse res) {
        customers.unblock(actor, id); return detailRedirect(id, req, res);
    }
    private static ModelAndView detailRedirect(UUID id, HttpServletRequest req, HttpServletResponse res) {
        return BrowserResponse.redirect(HtmxRequest.from(req), res, "/staff/customers/" + id);
    }
    private static ModelAndView render(HttpServletRequest req, HttpServletResponse res, String view, String fragment, Map<String,?> model) {
        return BrowserResponse.render(HtmxRequest.from(req), res, view, fragment, model);
    }
}
