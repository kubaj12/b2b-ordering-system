package io.github.kubaj12.online_store.inventory.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface InventoryStore {
    record Item(UUID skuId, String productName, String skuCode, String category, int quantity,
            long version, String changedBy, Instant changedAt) { }
    record Page(List<Item> items, int page, int size, long total) { }
    Page page(String search, String category, int page, int size);
    List<String> categories();
    record UpdateResult(boolean updated, int previousQuantity) { }
    UpdateResult update(UUID skuId, int quantity, long expectedVersion, UUID actorId);
}
