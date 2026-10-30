package io.github.kubaj12.online_store.cartordering.web;

import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import org.springframework.mock.web.MockHttpSession;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

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
    @Autowired io.github.kubaj12.online_store.testsupport.InMemoryAuthenticationAccountStore accounts;
    private CartService.CartView current;
    private final UUID cartId = UUID.randomUUID();
    @MockitoBean CheckoutReviewService reviews;

    @BeforeEach void cartHasAnAvailablePricedLine() {
        var line = new CartStore.Line(UUID.randomUUID(), "SKU-1", "Produkt", 2, 5, true, true);
        var price = new EffectivePriceResolver.EffectivePrice(new BigDecimal("10.00"), new BigDecimal("23.00"),
                EffectivePriceResolver.Source.BASE);
        var item = new CartService.PricedLine(line, price, DecimalPriceCalculator.line(price.unitNetPrice(), price.vatRate(), 2));
        current = new CartService.CartView(List.of(item), DecimalPriceCalculator.total(
                List.of(new DecimalPriceCalculator.LineInput(price.unitNetPrice(), price.vatRate(), 2))));
        when(reviews.refresh(nullable(String.class))).thenReturn(new CheckoutReviewService.ReviewState(cartId, current, false, false));
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
        verify(reviews, never()).issue(any(), nullable(String.class));
    }

    @Test void validCheckoutPostRendersTheIssuedTokenAndRecalculatedTotals() throws Exception {
        var expectedReview = new CheckoutReviewService.Review(current, "issued-review-token");
        when(reviews.issue(any(), nullable(String.class))).thenReturn(expectedReview);

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
        verify(reviews).issue(any(), nullable(String.class));
    }

    @Test void redirectsToCartWhenCartBecomesEmptyDuringReviewIssuance() throws Exception {
        var availableCart = current;
        var emptyCart = new CartService.CartView(List.of(), DecimalPriceCalculator.total(List.of()));
        when(reviews.refresh(nullable(String.class))).thenReturn(
                new CheckoutReviewService.ReviewState(cartId, availableCart, false, false),
                new CheckoutReviewService.ReviewState(cartId, emptyCart, false, true));
        when(reviews.issue(any(), nullable(String.class))).thenThrow(new IllegalStateException("Checkout requires a non-empty cart"));

        mvc.perform(validCheckoutPost())
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/cart"));
    }

    @Test void htmxRedirectsToCartWhenCartBecomesEmptyDuringReviewIssuance() throws Exception {
        var availableCart = current;
        var emptyCart = new CartService.CartView(List.of(), DecimalPriceCalculator.total(List.of()));
        when(reviews.refresh(nullable(String.class))).thenReturn(
                new CheckoutReviewService.ReviewState(cartId, availableCart, false, false),
                new CheckoutReviewService.ReviewState(cartId, emptyCart, false, true));
        when(reviews.issue(any(), nullable(String.class))).thenThrow(new IllegalStateException("Checkout requires a non-empty cart"));

        mvc.perform(validCheckoutPost().header("HX-Request", "true"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("HX-Redirect", "/cart"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validationErrorsPreserveEscapedInputAndClearUntrustedTokens(boolean htmx) throws Exception {
        var request = validCheckoutPost().param("purchaseOrderNumber", "<script>alert('PO')</script>")
                .param("unitNumber", "a".repeat(33)).param("checkoutToken", "untrusted-token")
                .param("billingCity", "INTERNAL-BILLING").param("comment", "UNSUPPORTED-COMMENT");
        if (htmx) request.header("HX-Request", "true");
        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(view().name(htmx ? "cart/checkout :: content" : "cart/checkout"))
                .andExpect(model().attributeHasFieldErrors("form", "unitNumber"))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert"))))
                .andExpect(content().string(containsString("Jan Kowalski")))
                .andExpect(content().string(not(containsString("untrusted-token"))))
                .andExpect(content().string(not(containsString("INTERNAL-BILLING"))))
                .andExpect(content().string(not(containsString("UNSUPPORTED-COMMENT"))));
        verify(reviews, never()).issue(any(), nullable(String.class));
        verify(reviews).invalidate();
    }

    @Test void safeDraftSurvivesReturningFromCartCorrectionButInvalidFieldsAreNotSaved() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(validCheckoutPost().session(session).param("purchaseOrderNumber", "PO-17")
                        .param("postalCode", "INVALID"))
                .andExpect(model().attributeHasFieldErrors("form", "postalCode"));
        mvc.perform(get("/cart/checkout").session(session).with(SecurityTestUsers.customer()))
                .andExpect(content().string(containsString("Jan Kowalski")))
                .andExpect(content().string(containsString("PO-17")))
                .andExpect(content().string(not(containsString("INVALID"))));
        mvc.perform(get("/cart/checkout").with(SecurityTestUsers.customer()))
                .andExpect(content().string(not(containsString("PO-17"))));
    }

    @Test void returningAfterACommercialChangePreservesDeliveryAndRequiresExplicitReview() throws Exception {
        var session = new MockHttpSession();
        when(reviews.issue(any(), nullable(String.class))).thenReturn(new CheckoutReviewService.Review(current, "old-issued-token"));
        mvc.perform(validCheckoutPost().session(session).param("purchaseOrderNumber", "PO-17"))
                .andExpect(content().string(containsString("old-issued-token")));
        when(reviews.refresh("old-issued-token")).thenReturn(new CheckoutReviewService.ReviewState(cartId, current, false, true));
        mvc.perform(get("/cart/checkout").session(session).with(SecurityTestUsers.customer()))
                .andExpect(content().string(containsString("Poprzednie podsumowanie straciło ważność")))
                .andExpect(content().string(containsString("PO-17")))
                .andExpect(content().string(containsString("Prosta")))
                .andExpect(content().string(not(containsString("old-issued-token"))));
        verify(reviews, org.mockito.Mockito.times(1)).issue(any(), nullable(String.class));
    }

    @Test void stalePostDisplaysUpdatedReviewAndOnlyTheFollowingPostIssuesAToken() throws Exception {
        when(reviews.refresh("stale-token")).thenReturn(new CheckoutReviewService.ReviewState(cartId, current, false, true));
        mvc.perform(validCheckoutPost().param("checkoutToken", "stale-token"))
                .andExpect(content().string(containsString("Poprzednie podsumowanie straciło ważność")))
                .andExpect(content().string(not(containsString("value=\"stale-token\""))));
        verify(reviews, never()).issue(any(), nullable(String.class));
        when(reviews.issue(any(), nullable(String.class))).thenReturn(new CheckoutReviewService.Review(current, "fresh-issued-token"));
        mvc.perform(validCheckoutPost()).andExpect(content().string(containsString("fresh-issued-token")));
        verify(reviews).issue(any(), nullable(String.class));
    }

    @Test void replacementActiveCartClearsThePriorOrderDraft() throws Exception {
        var session = new MockHttpSession();
        when(reviews.issue(any(), nullable(String.class))).thenReturn(new CheckoutReviewService.Review(current, "old-issued-token"));
        mvc.perform(validCheckoutPost().session(session).param("purchaseOrderNumber", "PO-17"));
        when(reviews.refresh(nullable(String.class))).thenReturn(new CheckoutReviewService.ReviewState(UUID.randomUUID(), current, false, true));
        mvc.perform(get("/cart/checkout").session(session).with(SecurityTestUsers.customer()))
                .andExpect(content().string(not(containsString("PO-17"))))
                .andExpect(content().string(not(containsString("Jan Kowalski"))));
    }

    @Test void draftDoesNotLeakToAnotherCustomerUsingTheSameSession() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(validCheckoutPost().session(session).param("purchaseOrderNumber", "PRIVATE-PO")
                .param("postalCode", "INVALID"));
        UUID otherId = UUID.randomUUID();
        accounts.put(new io.github.kubaj12.online_store.identityaccess.application.AuthenticationAccountStore.Credentials(
                otherId, "other@example.test", "unused", "CUSTOMER", "ACTIVE", 0));
        var other = SecurityTestUsers.principal(otherId, "other@example.test", "CUSTOMER");
        mvc.perform(get("/cart/checkout").session(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(other)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("PRIVATE-PO"))))
                .andExpect(content().string(not(containsString("Jan Kowalski"))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void issuanceDetectingAChangeAfterRefreshRendersUpdatedAmountsWithoutAToken(boolean htmx) throws Exception {
        when(reviews.refresh("prior-token")).thenReturn(new CheckoutReviewService.ReviewState(cartId, current, true, false));
        var oldLine = current.lines().getFirst().line();
        var newPrice = new EffectivePriceResolver.EffectivePrice(new BigDecimal("15.00"), new BigDecimal("23.00"),
                EffectivePriceResolver.Source.BASE);
        var newLine = new CartService.PricedLine(oldLine, newPrice,
                DecimalPriceCalculator.line(newPrice.unitNetPrice(), newPrice.vatRate(), oldLine.quantity()));
        var newCart = new CartService.CartView(List.of(newLine), newLine.amounts());
        when(reviews.issue(any(), eq("prior-token"))).thenReturn(new CheckoutReviewService.Review(newCart, null));
        var request = validCheckoutPost().param("checkoutToken", "prior-token").param("purchaseOrderNumber", "PO-17");
        if (htmx) request.header("HX-Request", "true");

        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(view().name(htmx ? "cart/checkout :: content" : "cart/checkout"))
                .andExpect(model().attribute("reviewIssued", false))
                .andExpect(model().attribute("reviewChanged", true))
                .andExpect(content().string(containsString("Razem brutto: 36,90 zł")))
                .andExpect(content().string(containsString("Poprzednie podsumowanie straciło ważność")))
                .andExpect(content().string(containsString("PO-17")))
                .andExpect(content().string(containsString("Jan Kowalski")))
                .andExpect(content().string(not(containsString("prior-token"))))
                .andExpect(content().string(not(containsString("Podsumowanie zostało przeliczone i zapisane"))));
        verify(reviews).issue(any(), eq("prior-token"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validCheckoutPost() {
        return post("/cart/checkout").with(SecurityTestUsers.customer()).with(csrf())
                .param("contactName", "Jan Kowalski").param("contactPhone", "+48 600 700 800")
                .param("street", "Prosta").param("buildingNumber", "1")
                .param("postalCode", "00-001").param("city", "Warszawa");
    }
}
