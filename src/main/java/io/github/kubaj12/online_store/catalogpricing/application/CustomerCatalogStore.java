package io.github.kubaj12.online_store.catalogpricing.application;

import java.util.List;
import java.util.UUID;

/** Read boundary for customer-visible catalog rows. */
public interface CustomerCatalogStore {
    record Variant(UUID id, String code, int quantity, boolean imagePresent,
            List<CatalogStore.VariantValue> attributes) { }
    record Product(UUID id, String name, String description, String category, List<Variant> variants) { }
    record ProductPage(List<Product> products, int page, int size, long total) { }
    ProductPage page(String search, String category, int page, int size);
    List<String> categories();
}
