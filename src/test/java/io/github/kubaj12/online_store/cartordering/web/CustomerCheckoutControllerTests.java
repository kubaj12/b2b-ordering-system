package io.github.kubaj12.online_store.cartordering.web;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.kubaj12.online_store.cartordering.application.CartService;
import io.github.kubaj12.online_store.cartordering.application.CheckoutReviewService;
import io.github.kubaj12.online_store.cartordering.application.CartStore;
import io.github.kubaj12.online_store.catalogpricing.application.EffectivePriceResolver;
import io.github.kubaj12.online_store.shared.money.DecimalPriceCalculator;
import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;
import io.github.kubaj12.online_store.testsupport.SecurityTestUsers;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@BrowserMvcTest(controllers = CustomerCheckoutController.class)
class CustomerCheckoutControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean CartService carts;
    @MockitoBean CheckoutReviewService reviews;

    @BeforeEach void cartHasAnAvailablePricedLine() {
        var line = new CartStore.Line(UUID.randomUUID(), "SKU-1", "Produkt", 2, 5, true, true);
        var price = new EffectivePriceResolver.EffectivePrice(new BigDecimal("10.00"), new BigDecimal("23.00"),
                EffectivePriceResolver.Source.BASE);
        var item = new CartService.PricedLine(line, price, DecimalPriceCalculator.line(price.unitNetPrice(), price.vatRate(), 2));
        when(carts.lines()).thenReturn(new CartService.CartView(List.of(item), DecimalPriceCalculator.total(
                List.of(new DecimalPriceCalculator.LineInput(price.unitNetPrice(), price.vatRate(), 2)))));
    }

    @Test void customerGetsCheckoutAndHtmxGetsOnlyTheContentFragment() throws Exception {
        mvc.perform(get("/cart/checkout").with(SecurityTestUsers.customer()))
                .andExpect(status().isOk()).andExpect(view().name("cart/checkout"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Dane dostawy i podsumowanie")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("NIP"))));
        mvc.perform(get("/cart/checkout").header("HX-Request", "true").with(SecurityTestUsers.customer()))
                .andExpect(status().isOk()).andExpect(view().name("cart/checkout :: content"));
    }

    @Test void staffCannotAccessAndPostRequiresCsrf() throws Exception {
        mvc.perform(get("/cart/checkout").with(SecurityTestUsers.employee())).andExpect(status().isForbidden());
        mvc.perform(post("/cart/checkout").with(SecurityTestUsers.customer())).andExpect(status().isForbidden());
    }

    @Test void malformedPhoneDoesNotIssueAReviewEvenWithValidCsrf() throws Exception {
        mvc.perform(post("/cart/checkout").with(SecurityTestUsers.customer()).with(csrf())
                        .param("contactName", "Jan Kowalski").param("contactPhone", "1        ")
                        .param("street", "Prosta").param("buildingNumber", "1")
                        .param("postalCode", "00-001").param("city", "Warszawa"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Telefon kontaktowy")));
        verify(reviews, never()).issue();
    }

    @Test void validCheckoutPostRendersTheIssuedTokenAndRecalculatedTotals() throws Exception {
        var expectedReview = new CheckoutReviewService.Review(carts.lines(), "issued-review-token");
        when(reviews.issue()).thenReturn(expectedReview);

        mvc.perform(post("/cart/checkout").with(SecurityTestUsers.customer()).with(csrf())
                        .param("purchaseOrderNumber", "PO-2026-17")
                        .param("contactName", "Jan Kowalski").param("contactPhone", "+48 600 700 800")
                        .param("street", "Prosta").param("buildingNumber", "1").param("unitNumber", "2")
                        .param("postalCode", "00-001").param("city", "Warszawa").param("country", "DE"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("issued-review-token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PO-2026-17")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Razem brutto: 24,60 zł")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("zapisane")));
        verify(reviews).issue();
    }

    @Test void redirectsToCartWhenCartBecomesEmptyDuringReviewIssuance() throws Exception {
        var availableCart = carts.lines();
        var emptyCart = new CartService.CartView(List.of(), DecimalPriceCalculator.total(List.of()));
        when(carts.lines()).thenReturn(availableCart, emptyCart);
        when(reviews.issue()).thenThrow(new IllegalStateException("Checkout requires a non-empty cart"));

        mvc.perform(validCheckoutPost())
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/cart"));
    }

    @Test void htmxRedirectsToCartWhenCartBecomesEmptyDuringReviewIssuance() throws Exception {
        var availableCart = carts.lines();
        var emptyCart = new CartService.CartView(List.of(), DecimalPriceCalculator.total(List.of()));
        when(carts.lines()).thenReturn(availableCart, emptyCart);
        when(reviews.issue()).thenThrow(new IllegalStateException("Checkout requires a non-empty cart"));

        mvc.perform(validCheckoutPost().header("HX-Request", "true"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("HX-Redirect", "/cart"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validCheckoutPost() {
        return post("/cart/checkout").with(SecurityTestUsers.customer()).with(csrf())
                .param("contactName", "Jan Kowalski").param("contactPhone", "+48 600 700 800")
                .param("street", "Prosta").param("buildingNumber", "1")
                .param("postalCode", "00-001").param("city", "Warszawa");
    }
}
