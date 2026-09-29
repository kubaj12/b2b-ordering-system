package io.github.kubaj12.online_store.catalogpricing.application;

import java.math.BigDecimal;
import java.util.UUID;
import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the current customer unit net price in the product-defined precedence order. */
@Service
public class EffectivePriceResolver {
    public enum Source { CUSTOMER_SPECIFIC, ASSIGNED_LIST, BASE }
    public record EffectivePrice(BigDecimal unitNetPrice, BigDecimal vatRate, Source source) { }

    private final PriceListStore store;

    public EffectivePriceResolver(PriceListStore store) { this.store = store; }

    @Transactional(readOnly = true)
    public EffectivePrice resolve(UUID skuId) {
        if (skuId == null) throw new CatalogException("SKU is required");
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountPrincipal principal)
                || !"CUSTOMER".equals(principal.role())) {
            throw new AccessDeniedException("An authenticated customer is required to resolve customer pricing");
        }
        UUID customerId = principal.accountId();
        PriceListStore.PriceCandidates candidates = store.priceCandidates(customerId, skuId);
        if (candidates == null) throw new CatalogException("customer or SKU not found");
        if (candidates.customerPrice() != null)
            return new EffectivePrice(candidates.customerPrice(), candidates.vatRate(), Source.CUSTOMER_SPECIFIC);
        if (candidates.assignedListPrice() != null)
            return new EffectivePrice(candidates.assignedListPrice(), candidates.vatRate(), Source.ASSIGNED_LIST);
        return new EffectivePrice(candidates.basePrice(), candidates.vatRate(), Source.BASE);
    }
}
