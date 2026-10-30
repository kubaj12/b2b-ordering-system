package io.github.kubaj12.online_store.cartordering.web;

import io.github.kubaj12.online_store.cartordering.application.CartService;
import io.github.kubaj12.online_store.cartordering.application.CheckoutReviewService;
import io.github.kubaj12.online_store.shared.web.request.BrowserResponse;
import io.github.kubaj12.online_store.shared.web.request.HtmxRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
@PreAuthorize("hasRole('CUSTOMER')")
@RequestMapping("/cart/checkout")
public class CustomerCheckoutController {
    private static final String DRAFT_ATTRIBUTE = CustomerCheckoutController.class.getName() + ".draft";
    private record Draft(String owner, java.util.UUID cartId, CheckoutForm form) { }
    private final CheckoutReviewService reviews;

    public CustomerCheckoutController(CheckoutReviewService reviews) {
        this.reviews = reviews;
    }

    @InitBinder("form")
    void bindOnlyCheckoutFields(WebDataBinder binder) {
        binder.setAllowedFields("purchaseOrderNumber", "contactName", "contactPhone", "street", "buildingNumber",
                "unitNumber", "postalCode", "city", "country", "checkoutToken");
    }

    @GetMapping
    public ModelAndView show(HttpServletRequest request, HttpServletResponse response) {
        var draft = draft(request);
        var form = draft == null ? new CheckoutForm() : draft.form().safeCopy(null);
        var state = reviews.refresh(form.getCheckoutToken());
        if (draft != null && !draft.cartId().equals(state.cartId())) form = new CheckoutForm();
        if (!state.reviewValid()) form.setCheckoutToken(null);
        saveDraft(request, state.cartId(), form, null);
        if (state.cart().lines().isEmpty()) return BrowserResponse.redirect(HtmxRequest.from(request), response, "/cart");
        return render(request, response, state.cart(), form,
                Map.of("reviewIssued", state.reviewValid(), "reviewChanged", state.reviewChanged()));
    }

    @PostMapping
    public ModelAndView review(@Valid @ModelAttribute("form") CheckoutForm form, BindingResult errors,
            HttpServletRequest request, HttpServletResponse response, Model model) {
        var priorToken = form.getCheckoutToken();
        var state = reviews.refresh(priorToken);
        var current = state.cart();
        form.setCheckoutToken(null); // Never reflect an unverified client token or retain it after errors.
        model.addAttribute("reviewIssued", false);
        model.addAttribute("reviewChanged", state.reviewChanged());
        if (errors.hasErrors() || hasUnavailableLines(current)) {
            reviews.invalidate();
        } else if (!state.reviewChanged() && !current.lines().isEmpty()) {
            try {
                var review = reviews.issue(form.toDetails(), priorToken);
                form.setCheckoutToken(review.token());
                current = review.cart();
                model.addAttribute("reviewIssued", review.issued());
                model.addAttribute("reviewChanged", !review.issued());
            } catch (IllegalStateException changed) {
                model.addAttribute("reviewChanged", true);
                current = reviews.refresh(null).cart();
            }
        }
        saveDraft(request, state.cartId(), form, errors);
        if (current.lines().isEmpty()) return BrowserResponse.redirect(HtmxRequest.from(request), response, "/cart");
        return render(request, response, current, form, model.asMap());
    }

    private static Draft draft(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        var value = session.getAttribute(DRAFT_ATTRIBUTE);
        if (value instanceof Draft draft && java.util.Objects.equals(draft.owner(), request.getRemoteUser())) return draft;
        return null;
    }

    private static void saveDraft(HttpServletRequest request, java.util.UUID cartId,
            CheckoutForm form, BindingResult errors) {
        request.getSession().setAttribute(DRAFT_ATTRIBUTE, new Draft(request.getRemoteUser(), cartId, form.safeCopy(errors)));
    }

    private ModelAndView render(HttpServletRequest request, HttpServletResponse response,
            CartService.CartView cart, CheckoutForm form, Map<String, ?> extra) {
        var attributes = new java.util.HashMap<String, Object>();
        attributes.put("cart", cart); attributes.put("form", form);
        attributes.put("cartUnavailable", cart.lines().stream().anyMatch(line -> !line.line().productActive()
                || !line.line().skuActive() || line.line().availableQuantity() < line.line().quantity()));
        attributes.put("lineIssues", cart.lines().stream().filter(line -> lineIssue(line) != null)
                .collect(java.util.stream.Collectors.toMap(line -> line.line().skuId(),
                        CustomerCheckoutController::lineIssue, (first, ignored) -> first)));
        if (extra != null) attributes.putAll(extra);
        return BrowserResponse.render(HtmxRequest.from(request), response, "cart/checkout", "cart/checkout :: content", attributes);
    }

    private static boolean hasUnavailableLines(CartService.CartView cart) {
        return cart.lines().stream().anyMatch(line -> lineIssue(line) != null);
    }

    private static String lineIssue(CartService.PricedLine line) {
        if (!line.line().productActive()) return "Produkt jest nieaktywny. Usuń pozycję z koszyka.";
        if (!line.line().skuActive()) return "Wariant SKU jest nieaktywny. Usuń pozycję z koszyka.";
        if (line.line().availableQuantity() < line.line().quantity()) {
            return line.line().availableQuantity() == 0
                    ? "Brak dostępnego stanu. Usuń pozycję z koszyka."
                    : "Dostępnych jest " + line.line().availableQuantity() + " szt. Zmień ilość w koszyku.";
        }
        return null;
    }
}
