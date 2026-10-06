package io.github.kubaj12.online_store.cartordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.kubaj12.online_store.catalogpricing.application.EffectivePriceResolver;
import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.mockito.ArgumentCaptor;

class CheckoutReviewServiceTests {
    private final CartStore store = org.mockito.Mockito.mock(CartStore.class);
    private final EffectivePriceResolver prices = org.mockito.Mockito.mock(EffectivePriceResolver.class);
    private final CheckoutReviewStore reviewStore = org.mockito.Mockito.mock(CheckoutReviewStore.class);
    private final UUID customerId = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();
    private final UUID skuId = UUID.randomUUID();

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void displaysAndPersistsTheSameSinglePriceResolution() throws Exception {
        authenticateCustomer();
        var cart = new CartStore.Cart(cartId, customerId, CartStore.Status.ACTIVE, 7,
                Instant.parse("2026-10-06T10:00:00Z"), Instant.parse("2026-10-06T10:00:00Z"), null);
        var line = new CartStore.Line(skuId, "SKU-1", "Produkt", 2, 8, true, true);
        when(store.findOrCreateActive(customerId)).thenReturn(cart);
        when(store.lines(cartId)).thenReturn(List.of(line));
        var firstResolution = new EffectivePriceResolver.EffectivePrice(
                new BigDecimal("12.34"), new BigDecimal("23.00"), EffectivePriceResolver.Source.BASE);
        var laterConcurrentPrice = new EffectivePriceResolver.EffectivePrice(
                new BigDecimal("99.99"), new BigDecimal("8.00"), EffectivePriceResolver.Source.BASE);
        when(prices.resolveAll(List.of(skuId))).thenReturn(Map.of(skuId, firstResolution), Map.of(skuId, laterConcurrentPrice));
        var service = new CheckoutReviewService(new CartService(store, prices), store, prices, reviewStore,
                Clock.fixed(Instant.parse("2026-10-06T10:01:00Z"), ZoneOffset.UTC));

        var review = service.issue();

        assertThat(review.cart().lines().getFirst().price()).isEqualTo(firstResolution);
        assertThat(review.cart().lines().getFirst().amounts().net()).isEqualByComparingTo("24.68");
        assertThat(review.cart().lines().getFirst().amounts().vat()).isEqualByComparingTo("5.68");
        assertThat(review.cart().totals().gross()).isEqualByComparingTo("30.36");
        verify(prices, times(1)).resolveAll(List.of(skuId));

        ArgumentCaptor<CheckoutReviewStore.ReviewSnapshot> reviewArguments =
                ArgumentCaptor.forClass(CheckoutReviewStore.ReviewSnapshot.class);
        verify(reviewStore).save(reviewArguments.capture());
        assertThat(reviewArguments.getValue().cartId()).isEqualTo(cartId);
        assertThat(reviewArguments.getValue().customerId()).isEqualTo(customerId);
        assertThat(reviewArguments.getValue().cartRevision()).isEqualTo(7L);
        var rawToken = java.util.Base64.getUrlDecoder().decode(review.token());
        assertThat(reviewArguments.getValue().tokenHash()).isEqualTo(MessageDigest.getInstance("SHA-256").digest(rawToken));

        var savedItem = reviewArguments.getValue().items().getFirst();
        assertThat(savedItem.unitNetPrice()).isEqualTo(new BigDecimal("12.34"));
        assertThat(savedItem.vatRate()).isEqualTo(new BigDecimal("23.00"));
        verify(reviewStore).invalidateActiveForCart(cartId, Instant.parse("2026-10-06T10:01:00Z"));
        assertThat(review.token()).isNotBlank();
    }

    @Test
    void doesNotIssueReviewsForEmptyOrUnavailableCarts() {
        authenticateCustomer();
        var cart = new CartStore.Cart(cartId, customerId, CartStore.Status.ACTIVE, 2,
                Instant.parse("2026-10-06T10:00:00Z"), Instant.parse("2026-10-06T10:00:00Z"), null);
        when(store.findOrCreateActive(customerId)).thenReturn(cart);
        var service = new CheckoutReviewService(new CartService(store, prices), store, prices, reviewStore,
                Clock.fixed(Instant.parse("2026-10-06T10:01:00Z"), ZoneOffset.UTC));

        when(store.lines(cartId)).thenReturn(List.of());
        assertThatThrownBy(service::issue).isInstanceOf(IllegalStateException.class)
                .hasMessage("Checkout requires a non-empty cart");
        when(store.lines(cartId)).thenReturn(List.of(new CartStore.Line(skuId, "SKU-1", "Produkt", 2, 1, true, true)));
        assertThatThrownBy(service::issue).isInstanceOf(IllegalStateException.class)
                .hasMessage("Checkout cart contains unavailable items");
        verifyNoInteractions(prices, reviewStore);
    }

    private void authenticateCustomer() {
        var principal = new AccountPrincipal(customerId, "buyer@example.test", null, "CUSTOMER", "ACTIVE", 0);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
    }
}
