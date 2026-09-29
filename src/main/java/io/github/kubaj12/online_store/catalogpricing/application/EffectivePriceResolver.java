package io.github.kubaj12.online_store.catalogpricing.application;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
        UUID customerId = authenticatedCustomerId();
        PriceListStore.PriceCandidates candidates = store.priceCandidates(customerId, skuId);
        return effective(candidates);
    }

    @Transactional(readOnly = true)
    public Map<UUID, EffectivePrice> resolveAll(List<UUID> skuIds) {
        if (skuIds == null || skuIds.stream().anyMatch(java.util.Objects::isNull)) throw new CatalogException("SKUs are required");
        UUID customerId = authenticatedCustomerId();
        var candidates=store.priceCandidates(customerId,skuIds);
        var result=new LinkedHashMap<UUID,EffectivePrice>();
        for(UUID id:skuIds) result.put(id,effective(candidates.get(id)));
        return Map.copyOf(result);
    }

    /**
     * Customer-facing pricing deliberately has no customer/list/override ID parameter.
     * The only customer scope is the active customer identity established by Spring Security.
     */
    private static UUID authenticatedCustomerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountPrincipal principal)
                || !"CUSTOMER".equals(principal.role()) || !principal.isEnabled()
                || principal.accountId() == null) {
            throw new AccessDeniedException("An active authenticated customer is required to resolve customer pricing");
        }
        return principal.accountId();
    }

    private static EffectivePrice effective(PriceListStore.PriceCandidates candidates) {
        if (candidates == null) throw new CatalogException("customer or SKU not found");
        if (candidates.customerPrice() != null)
            return new EffectivePrice(candidates.customerPrice(), candidates.vatRate(), Source.CUSTOMER_SPECIFIC);
        if (candidates.assignedListPrice() != null)
            return new EffectivePrice(candidates.assignedListPrice(), candidates.vatRate(), Source.ASSIGNED_LIST);
        return new EffectivePrice(candidates.basePrice(), candidates.vatRate(), Source.BASE);
    }
}
