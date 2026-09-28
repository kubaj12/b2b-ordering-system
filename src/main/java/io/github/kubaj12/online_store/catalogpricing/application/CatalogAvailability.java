package io.github.kubaj12.online_store.catalogpricing.application;

/** Shared guard for cart insertion and order submission. */
public final class CatalogAvailability {
    private CatalogAvailability() { }
    public static void requireSellable(boolean productActive, boolean skuActive) {
        if (!productActive || !skuActive) throw new CatalogException("inactive product or SKU cannot be ordered");
    }
}
