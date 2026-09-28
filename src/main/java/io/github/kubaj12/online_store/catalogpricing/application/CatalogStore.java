package io.github.kubaj12.online_store.catalogpricing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Persistence boundary for catalog maintenance use cases. */
public interface CatalogStore {
    record Availability(boolean productActive, boolean skuActive) { }
    record PriceVat(BigDecimal price, BigDecimal vat) { }
    Availability availability(UUID skuId);
    void createProduct(UUID id, String name, String description, String category, Instant now);
    void updateProduct(UUID id, String name, String description, String category, Instant now);
    boolean activateProduct(UUID id, Instant now);
    void deactivateProduct(UUID id, Instant now);
    void createSku(UUID id, UUID productId, String code, BigDecimal price, BigDecimal vat, Instant now);
    void updateSku(UUID id, String code, Instant now);
    void setSkuActivity(UUID id, boolean active, Instant now);
    PriceVat setPriceAndVat(UUID id, BigDecimal price, BigDecimal vat, Instant now);
    void assignAttribute(UUID skuId, UUID definitionId, UUID valueId, Instant now);
    void removeAttribute(UUID skuId, UUID definitionId);
    UUID createDefinition(String name, Instant now);
    UUID createAttributeValue(UUID definitionId, String value, Instant now);
}
