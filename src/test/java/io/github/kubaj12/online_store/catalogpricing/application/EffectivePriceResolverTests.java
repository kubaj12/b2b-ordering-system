package io.github.kubaj12.online_store.catalogpricing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;

class EffectivePriceResolverTests {
    private final PriceListStore store = mock(PriceListStore.class);
    private final EffectivePriceResolver resolver = new EffectivePriceResolver(store);
    private final UUID signedInCustomer = UUID.randomUUID();
    private final UUID skuId = UUID.randomUUID();

    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test void usesAuthenticatedCustomerIdentityAndCustomerSpecificPrecedence() {
        authenticate(signedInCustomer, "CUSTOMER");
        when(store.priceCandidates(signedInCustomer, skuId)).thenReturn(
                new PriceListStore.PriceCandidates(new BigDecimal("7.00"), new BigDecimal("8.00"),
                        new BigDecimal("9.00"), new BigDecimal("23.00")));

        var price = resolver.resolve(skuId);

        assertThat(price.unitNetPrice()).isEqualByComparingTo("7.00");
        assertThat(price.vatRate()).isEqualByComparingTo("23.00");
        assertThat(price.source()).isEqualTo(EffectivePriceResolver.Source.CUSTOMER_SPECIFIC);
        verify(store).priceCandidates(signedInCustomer, skuId);
    }

    @Test void fallsBackToAssignedListThenBasePrice() {
        authenticate(signedInCustomer, "CUSTOMER");
        when(store.priceCandidates(signedInCustomer, skuId)).thenReturn(
                new PriceListStore.PriceCandidates(null, new BigDecimal("8.00"),
                        new BigDecimal("9.00"), new BigDecimal("23.00")));
        assertThat(resolver.resolve(skuId).source()).isEqualTo(EffectivePriceResolver.Source.ASSIGNED_LIST);

        when(store.priceCandidates(signedInCustomer, skuId)).thenReturn(
                new PriceListStore.PriceCandidates(null, null, new BigDecimal("9.00"), new BigDecimal("23.00")));
        var base = resolver.resolve(skuId);
        assertThat(base.unitNetPrice()).isEqualByComparingTo("9.00");
        assertThat(base.source()).isEqualTo(EffectivePriceResolver.Source.BASE);
    }

    @Test void doesNotAllowAStaffPrincipalToResolveCustomerPrices() {
        authenticate(signedInCustomer, "EMPLOYEE");
        assertThatThrownBy(() -> resolver.resolve(skuId)).isInstanceOf(AccessDeniedException.class);
    }

    private static void authenticate(UUID customerId, String role) {
        var principal = new AccountPrincipal(customerId, "customer@example.test", "hash", role, "ACTIVE", 0);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
}
