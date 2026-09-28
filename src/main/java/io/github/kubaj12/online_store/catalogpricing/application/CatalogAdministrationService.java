package io.github.kubaj12.online_store.catalogpricing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.kubaj12.online_store.shared.auditing.AuditActor;
import io.github.kubaj12.online_store.shared.auditing.AuditEventRecorder;

@Service
public class CatalogAdministrationService {
    private final CatalogStore store;
    private final Clock clock;
    private final AuditEventRecorder audit;
    public CatalogAdministrationService(CatalogStore store, Clock clock, AuditEventRecorder audit) {
        this.store = store; this.clock = clock; this.audit = audit;
    }

    @Transactional public UUID createProduct(String name, String description, String category) {
        String n = required(name, 255, "product name"), c = required(category, 120, "category");
        String d = description(description);
        UUID id = UUID.randomUUID(); store.createProduct(id, n, d, c, now()); return id;
    }
    @Transactional public void updateProduct(UUID id, String name, String description, String category) {
        store.updateProduct(requiredId(id), required(name,255,"product name"), description(description),
                required(category,120,"category"), now());
    }
    @Transactional public void activateProduct(UUID id) {
        if (!store.activateProduct(requiredId(id), now())) throw new CatalogException("product must have at least one SKU before activation");
    }
    /** Retains product and SKU rows so order references and audit/history remain valid. */
    @Transactional public void deactivateProduct(UUID id) { store.deactivateProduct(requiredId(id), now()); }
    @Transactional public UUID createSku(UUID productId, String code, BigDecimal baseNetPrice, BigDecimal vatRate, UUID actorId) {
        UUID id = UUID.randomUUID();
        BigDecimal price = money(baseNetPrice), rate = vat(vatRate);
        store.createSku(id, requiredId(productId), required(code,80,"SKU code"), price, rate, now());
        audit.record(CatalogAudit.SKU_PRICING_CHANGED.event(id, new AuditActor(requiredId(actorId)),
                CatalogAudit.BASE_NET_PRICE.change(null, price), CatalogAudit.VAT_RATE.change(null, rate)));
        return id;
    }
    @Transactional public void updateSku(UUID id, String code) { store.updateSku(requiredId(id), required(code,80,"SKU code"), now()); }
    @Transactional public void activateSku(UUID id) { store.setSkuActivity(requiredId(id), true, now()); }
    @Transactional public void deactivateSku(UUID id) { store.setSkuActivity(requiredId(id), false, now()); }
    @Transactional public void changeBasePriceAndVat(UUID id, BigDecimal price, BigDecimal vat, UUID actorId) {
        UUID skuId = requiredId(id);
        BigDecimal nextPrice = money(price), nextVat = vat(vat);
        CatalogStore.PriceVat previous = store.setPriceAndVat(skuId, nextPrice, nextVat, now());
        var actor = new AuditActor(requiredId(actorId));
        if (previous.price().compareTo(nextPrice) != 0 || previous.vat().compareTo(nextVat) != 0) {
            var priceChange = previous.price().compareTo(nextPrice) == 0 ? null : CatalogAudit.BASE_NET_PRICE.change(previous.price(), nextPrice);
            var vatChange = previous.vat().compareTo(nextVat) == 0 ? null : CatalogAudit.VAT_RATE.change(previous.vat(), nextVat);
            if (priceChange != null && vatChange != null) audit.record(CatalogAudit.SKU_PRICING_CHANGED.event(skuId, actor, priceChange, vatChange));
            else if (priceChange != null) audit.record(CatalogAudit.SKU_PRICING_CHANGED.event(skuId, actor, priceChange));
            else audit.record(CatalogAudit.SKU_PRICING_CHANGED.event(skuId, actor, vatChange));
        }
    }
    @Transactional public UUID createAttributeDefinition(String name) { return store.createDefinition(required(name,120,"attribute name"), now()); }
    @Transactional public UUID createAttributeValue(UUID definitionId, String value) {
        return store.createAttributeValue(requiredId(definitionId), required(value,120,"attribute value"), now());
    }
    @Transactional public void assignVariantAttribute(UUID skuId, UUID definitionId, UUID valueId) {
        store.assignAttribute(requiredId(skuId), requiredId(definitionId), requiredId(valueId), now());
    }
    @Transactional public void removeVariantAttribute(UUID skuId, UUID definitionId) { store.removeAttribute(requiredId(skuId), requiredId(definitionId)); }
    @Transactional(readOnly = true) public void requireSellable(UUID skuId) {
        CatalogStore.Availability state = store.availability(requiredId(skuId));
        CatalogAvailability.requireSellable(state.productActive(), state.skuActive());
    }

    private static String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw new CatalogException("invalid " + label);
        return value.trim();
    }
    private static String description(String value) {
        String result = value == null ? "" : value;
        if (result.length() > 10000) throw new CatalogException("description is too long");
        return result;
    }
    private static UUID requiredId(UUID id) { if (id == null) throw new CatalogException("identifier required"); return id; }
    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0) throw new CatalogException("base net price must be non-negative");
        try { BigDecimal scaled = value.setScale(2, RoundingMode.UNNECESSARY); if (scaled.precision() > 12) throw new ArithmeticException(); return scaled; }
        catch (ArithmeticException ex) { throw new CatalogException("base net price must fit NUMERIC(12,2)", ex); }
    }
    private static BigDecimal vat(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) throw new CatalogException("VAT must be between 0 and 100");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException ex) { throw new CatalogException("VAT supports at most two decimals", ex); }
    }
    private java.time.Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
