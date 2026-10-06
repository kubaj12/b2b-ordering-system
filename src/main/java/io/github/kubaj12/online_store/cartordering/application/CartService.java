package io.github.kubaj12.online_store.cartordering.application;

import java.util.UUID;
import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.math.BigDecimal;
import java.util.ArrayList;
import io.github.kubaj12.online_store.catalogpricing.application.EffectivePriceResolver;
import io.github.kubaj12.online_store.shared.money.DecimalPriceCalculator;
import io.github.kubaj12.online_store.shared.web.error.WebErrorException;

/** Customer-scoped access to the persistent active cart. */
@Service
public class CartService {
    private final CartStore store;
    private final EffectivePriceResolver prices;

    public record PricedLine(CartStore.Line line, EffectivePriceResolver.EffectivePrice price,
            DecimalPriceCalculator.Amounts amounts) { }
    public record CartView(List<PricedLine> lines, DecimalPriceCalculator.Amounts totals) { }

    public CartService(CartStore store, EffectivePriceResolver prices) {
        this.store = store;
        this.prices = prices;
    }

    /**
     * Lazily creates a cart for a customer and reuses that same database row on
     * subsequent requests, including requests from a different login session.
     */
    @Transactional
    public CartStore.Cart activeCart() {
        return store.findOrCreateActive(authenticatedCustomerId());
    }

    /**
     * Used by edit flows: after checkout completes the previous cart, the first
     * subsequent edit obtains a new active cart through the same lazy operation.
     */
    @Transactional
    public CartStore.Cart cartForEdit() {
        return activeCart();
    }

    @Transactional
    public CartView lines() {
        var lines = store.lines(store.findOrCreateActive(authenticatedCustomerId()).id());
        var effective = prices.resolveAll(lines.stream().map(CartStore.Line::skuId).toList());
        return priceLines(lines, effective);
    }

    /** Prices a supplied cart snapshot from one resolution result, for checkout snapshot consistency. */
    public CartView priceLines(List<CartStore.Line> lines,
            java.util.Map<UUID, EffectivePriceResolver.EffectivePrice> effective) {
        var priced = new ArrayList<PricedLine>();
        var inputs = new ArrayList<DecimalPriceCalculator.LineInput>();
        for (var line : lines) {
            var price = effective.get(line.skuId());
            var amounts = DecimalPriceCalculator.line(price.unitNetPrice(), price.vatRate(), line.quantity());
            priced.add(new PricedLine(line, price, amounts));
            inputs.add(new DecimalPriceCalculator.LineInput(price.unitNetPrice(), price.vatRate(), line.quantity()));
        }
        return new CartView(List.copyOf(priced), DecimalPriceCalculator.total(inputs));
    }

    @Transactional
    public CartStore.Cart activeCartForCheckout() {
        return store.findOrCreateActive(authenticatedCustomerId());
    }

    @Transactional
    public void add(UUID skuId, int quantity) {
        requirePositive(quantity);
        var cart = store.findOrCreateActive(authenticatedCustomerId());
        var sellable = store.sellability(skuId);
        if (!sellable.found() || !sellable.productActive() || !sellable.skuActive())
            throw WebErrorException.conflict("cart.sku.inactive");
        if (sellable.stock() <= 0) throw WebErrorException.conflict("cart.sku.out-of-stock");
        Integer previous = store.quantity(cart.id(), skuId);
        if (previous != null && quantity > Integer.MAX_VALUE - previous)
            throw WebErrorException.validation("cart.quantity.overflow");
        if (previous == null && store.countLines(cart.id()) >= 500)
            throw WebErrorException.validation("cart.lines.limit");
        store.add(cart.id(), skuId, quantity);
    }

    @Transactional
    public void update(UUID skuId, int quantity) {
        requirePositive(quantity);
        var cart = store.findOrCreateActive(authenticatedCustomerId());
        Integer previous = store.quantity(cart.id(), skuId);
        if (previous == null) throw WebErrorException.notFound("cart.line.not-found");
        if (quantity > previous) {
            var sellable = store.sellability(skuId);
            if (!sellable.found() || !sellable.productActive() || !sellable.skuActive())
                throw WebErrorException.conflict("cart.sku.inactive");
            if (sellable.stock() <= 0) throw WebErrorException.conflict("cart.sku.out-of-stock");
        }
        // Updating an existing line remains possible at the 500-line boundary and
        // intentionally does not require the SKU to remain sellable.
        store.update(cart.id(), skuId, quantity);
    }

    @Transactional
    public void remove(UUID skuId) {
        var cart = store.findOrCreateActive(authenticatedCustomerId());
        // Removal is deliberately unconditional so invalid/unavailable lines can be cleared.
        store.remove(cart.id(), skuId);
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) throw WebErrorException.validation("cart.quantity.positive");
    }

    private static UUID authenticatedCustomerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountPrincipal principal)
                || !"CUSTOMER".equals(principal.role()) || !principal.isEnabled()
                || principal.accountId() == null) {
            throw new AccessDeniedException("An active authenticated customer is required to access a cart");
        }
        return principal.accountId();
    }
}
