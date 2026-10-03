package io.github.kubaj12.online_store.cartordering.web;

import io.github.kubaj12.online_store.cartordering.application.CartService;
import io.github.kubaj12.online_store.shared.web.request.BrowserResponse;
import io.github.kubaj12.online_store.shared.web.request.HtmxRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.ModelAndView;

@Controller
@PreAuthorize("hasRole('CUSTOMER')")
@RequestMapping("/cart")
public class CustomerCartController {
    private final CartService carts;
    public CustomerCartController(CartService carts) { this.carts = carts; }

    @GetMapping
    public ModelAndView show(HttpServletRequest request, HttpServletResponse response) {
        return BrowserResponse.render(HtmxRequest.from(request), response, "cart/customer-cart", "cart/customer-cart :: content",
                Map.of("lines", carts.lines()));
    }

    @PostMapping("/items")
    public ModelAndView add(@RequestParam UUID skuId, @RequestParam int quantity, RedirectAttributes flash,
            HttpServletRequest request, HttpServletResponse response) {
        carts.add(skuId, quantity);
        flash.addFlashAttribute("flashSuccessMessage", "Pozycja została dodana do koszyka.");
        return BrowserResponse.redirect(HtmxRequest.from(request), response, "/cart");
    }

    @PostMapping("/items/{skuId}")
    public ModelAndView update(@PathVariable UUID skuId, @RequestParam int quantity,
            HttpServletRequest request, HttpServletResponse response) {
        carts.update(skuId, quantity);
        return BrowserResponse.redirect(HtmxRequest.from(request), response, "/cart");
    }

    @PostMapping("/items/{skuId}/remove")
    public ModelAndView remove(@PathVariable UUID skuId, HttpServletRequest request, HttpServletResponse response) {
        carts.remove(skuId);
        return BrowserResponse.redirect(HtmxRequest.from(request), response, "/cart");
    }
}
