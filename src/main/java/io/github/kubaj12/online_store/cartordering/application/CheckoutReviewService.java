package io.github.kubaj12.online_store.cartordering.application;

import io.github.kubaj12.online_store.catalogpricing.application.EffectivePriceResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues customer/cart bound, short-lived reviews containing server-resolved prices only. */
@Service
public class CheckoutReviewService {
    private final CartService carts;
    private final CartStore store;
    private final EffectivePriceResolver prices;
    private final CheckoutReviewStore reviews;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public CheckoutReviewService(CartService carts, CartStore store, EffectivePriceResolver prices,
            CheckoutReviewStore reviews, Clock clock) {
        this.carts = carts; this.store = store; this.prices = prices; this.reviews = reviews; this.clock = clock;
    }

    public record Review(CartService.CartView cart, String token) { }

    @Transactional
    public Review issue() {
        var cart = carts.activeCartForCheckout();
        var lines = store.lines(cart.id());
        if (lines.isEmpty()) throw new IllegalStateException("Checkout requires a non-empty cart");
        if (lines.stream().anyMatch(line -> !line.productActive() || !line.skuActive()
                || line.availableQuantity() < line.quantity()))
            throw new IllegalStateException("Checkout cart contains unavailable items");
        // Resolve once: the exact values displayed below are the values persisted into this review.
        var effective = prices.resolveAll(lines.stream().map(CartStore.Line::skuId).toList());
        var cartView = carts.priceLines(lines, effective);
        byte[] raw = new byte[32]; random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID reviewId = UUID.randomUUID();
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        var items = new ArrayList<CheckoutReviewStore.ReviewedItem>();
        for (var line : lines) {
            var price = effective.get(line.skuId());
            items.add(new CheckoutReviewStore.ReviewedItem(line.skuId(), line.quantity(), price.unitNetPrice(), price.vatRate(),
                    sha256(price.unitNetPrice().stripTrailingZeros().toPlainString().getBytes(StandardCharsets.UTF_8)),
                    sha256(price.vatRate().stripTrailingZeros().toPlainString().getBytes(StandardCharsets.UTF_8))));
        }
        reviews.invalidateActiveForCart(cart.id(), now.toInstant());
        reviews.save(new CheckoutReviewStore.ReviewSnapshot(reviewId, cart.id(), cart.customerId(), sha256(raw),
                cart.revision(), now.toInstant(), now.plusMinutes(30).toInstant(), List.copyOf(items)));
        return new Review(cartView, token);
    }

    private static byte[] sha256(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
