package io.github.kubaj12.online_store.catalogpricing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PriceListStore {
    record Sku(UUID id, String code, String product, BigDecimal basePrice) { }
    record Customer(UUID id, String email, String company, UUID assignedListId, String assignedListName) { }
    record Item(UUID skuId, String code, String product, BigDecimal price) { }
    record CustomerPrice(UUID customerId, UUID skuId, String code, String product, BigDecimal price) { }
    record PriceList(UUID id, String name, int itemCount, List<Item> items) { }
    record CustomerPricePage(List<CustomerPrice> rows, int page, int totalPages) { }
    List<PriceList> lists();
    List<Sku> skus();
    UUID skuIdByCode(String code);
    List<Customer> customers();
    CustomerPricePage customerPrices(UUID customerId, int page, int pageSize);
    PriceList list(UUID id);
    boolean priceListExists(UUID id);
    BigDecimal listPrice(UUID listId, UUID skuId);
    BigDecimal customerPrice(UUID customerId, UUID skuId);
    void lockSku(UUID skuId);
    void lockCustomer(UUID customerId);
    void createList(UUID id, String name, Instant now);
    String renameList(UUID id, String name, Instant now);
    void saveListPrice(UUID listId, UUID skuId, BigDecimal price, Instant now);
    void removeListPrice(UUID listId, UUID skuId);
    UUID assignedList(UUID customerId);
    void assignList(UUID customerId, UUID listId, Instant now);
    void unassignList(UUID customerId);
    void saveCustomerPrice(UUID customerId, UUID skuId, BigDecimal price, Instant now);
    void removeCustomerPrice(UUID customerId, UUID skuId);
}
