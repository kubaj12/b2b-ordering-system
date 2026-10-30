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
import java.util.Objects;
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

    /** A null token means the supplied review became stale and these amounts must be reviewed again. */
    public record Review(CartService.CartView cart, String token) {
        public boolean issued() { return token != null; }
    }
    public record ReviewState(UUID cartId, CartService.CartView cart, boolean reviewValid, boolean reviewChanged) { }

    /** Refreshes the displayed amounts and expires stale reviews without issuing a replacement token. */
    @Transactional
    public ReviewState refresh(String token) {
        var cart = carts.activeCartForCheckout(); // Locks the same cart row as every cart writer.
        var lines = store.lines(cart.id());
        var effective = prices.resolveAll(lines.stream().map(CartStore.Line::skuId).toList());
        var current = carts.priceLines(lines, effective);
        return checkReview(cart, current, token);
    }

    /** Runs under the caller's cart lock, comparing the exact amounts that caller resolved. */
    private ReviewState checkReview(CartStore.Cart cart, CartService.CartView current, String token) {
        var active = reviews.activeForCart(cart.id(), cart.customerId());
        boolean changed = active.stream().anyMatch(review -> !matches(review, cart, current));
        if (changed) reviews.invalidateActiveForCart(cart.id(), clock.instant());
        byte[] hash = tokenHash(token);
        boolean valid = !changed && hash != null && active.stream()
                .anyMatch(review -> MessageDigest.isEqual(review.tokenHash(), hash));
        return new ReviewState(cart.id(), current, valid, changed || (token != null && !token.isBlank() && !valid));
    }

    @Transactional
    public void invalidate() {
        var cart = carts.activeCartForCheckout();
        reviews.invalidateActiveForCart(cart.id(), clock.instant());
    }

    private boolean matches(CheckoutReviewStore.ReviewSnapshot review, CartStore.Cart cart,
            CartService.CartView current) {
        if (!review.expiresAt().isAfter(clock.instant()) || review.cartRevision() != cart.revision()
                || current.lines().isEmpty() || review.items().size() != current.lines().size()) return false;
        var items = review.items().stream().collect(java.util.stream.Collectors.toMap(
                CheckoutReviewStore.ReviewedItem::skuId, item -> item));
        for (var line : current.lines()) {
            var item = items.get(line.line().skuId());
            if (item == null || item.quantity() != line.line().quantity()
                    || !line.line().productActive() || !line.line().skuActive()
                    || line.line().availableQuantity() < line.line().quantity()
                    || item.unitNetPrice().compareTo(line.price().unitNetPrice()) != 0
                    || item.vatRate().compareTo(line.price().vatRate()) != 0) return false;
        }
        return true;
    }

    private static byte[] tokenHash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        byte[] raw = Base64.getUrlDecoder().decode(token);
        return raw.length == 32 ? sha256(raw) : null;
    }

    @Transactional
    public Review issue(CheckoutDetails details, String priorToken) {
        Objects.requireNonNull(details, "Validated checkout details are required");
        var cart = carts.activeCartForCheckout();
        var lines = store.lines(cart.id());
        if (lines.isEmpty()) throw new IllegalStateException("Checkout requires a non-empty cart");
        if (lines.stream().anyMatch(line -> !line.productActive() || !line.skuActive()
                || line.availableQuantity() < line.quantity()))
            throw new IllegalStateException("Checkout cart contains unavailable items");
        // Resolve once: the exact values displayed below are the values persisted into this review.
        var effective = prices.resolveAll(lines.stream().map(CartStore.Line::skuId).toList());
        var cartView = carts.priceLines(lines, effective);
        // The controller's earlier refresh has released its lock. Recheck the submitted review here,
        // under the issuance lock and against the same price/VAT resolution used by the new snapshot.
        // Return normally so stale-review invalidation commits even when no replacement is issued.
        if (checkReview(cart, cartView, priorToken).reviewChanged()) return new Review(cartView, null);
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
