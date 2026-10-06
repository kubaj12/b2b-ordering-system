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
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
@PreAuthorize("hasRole('CUSTOMER')")
@RequestMapping("/cart/checkout")
public class CustomerCheckoutController {
    private final CartService carts;
    private final CheckoutReviewService reviews;
    public CustomerCheckoutController(CartService carts, CheckoutReviewService reviews) {
        this.carts = carts; this.reviews = reviews;
    }

    @GetMapping
    public ModelAndView show(HttpServletRequest request, HttpServletResponse response) {
        var view = carts.lines();
        if (view.lines().isEmpty()) return BrowserResponse.redirect(HtmxRequest.from(request), response, "/cart");
        return render(request, response, view, new CheckoutForm(), null);
    }

    @PostMapping
    public ModelAndView review(@Valid @ModelAttribute("form") CheckoutForm form, BindingResult errors,
            HttpServletRequest request, HttpServletResponse response, Model model) {
        var current = carts.lines();
        if (current.lines().isEmpty()) return BrowserResponse.redirect(HtmxRequest.from(request), response, "/cart");
        boolean unavailable = current.lines().stream().anyMatch(line -> !line.line().productActive()
                || !line.line().skuActive() || line.line().availableQuantity() < line.line().quantity());
        if (unavailable) model.addAttribute("cartUnavailable", true);
        if (!errors.hasErrors() && !unavailable) {
            try {
                var review = reviews.issue();
                form.setCheckoutToken(review.token());
                current = review.cart();
                model.addAttribute("reviewIssued", true);
            } catch (IllegalStateException changed) {
                model.addAttribute("cartUnavailable", true);
            }
        }
        return render(request, response, current, form, model.asMap());
    }

    private ModelAndView render(HttpServletRequest request, HttpServletResponse response,
            CartService.CartView cart, CheckoutForm form, Map<String, ?> extra) {
        var attributes = new java.util.HashMap<String, Object>();
        attributes.put("cart", cart); attributes.put("form", form);
        attributes.put("cartUnavailable", cart.lines().stream().anyMatch(line -> !line.line().productActive()
                || !line.line().skuActive() || line.line().availableQuantity() < line.line().quantity()));
        if (extra != null) attributes.putAll(extra);
        return BrowserResponse.render(HtmxRequest.from(request), response, "cart/checkout", "cart/checkout :: content", attributes);
    }
}
