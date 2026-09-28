package io.github.kubaj12.online_store.catalogpricing.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import io.github.kubaj12.online_store.catalogpricing.application.CatalogException;
import io.github.kubaj12.online_store.catalogpricing.application.CatalogStore;

@Repository
public class JdbcCatalogStore implements CatalogStore {
    private final JdbcTemplate jdbc;
    public JdbcCatalogStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Availability availability(UUID skuId) {
        return jdbc.query("""
            SELECT p.is_active, s.is_active FROM catalog_sku s
            JOIN catalog_product p ON p.id=s.product_id WHERE s.id=?
            """, rs -> { if (!rs.next()) throw new CatalogException("SKU not found"); return new Availability(rs.getBoolean(1),rs.getBoolean(2)); },skuId);
    }
    @Override public void createProduct(UUID id, String name, String description, String category, Instant now) {
        jdbc.update("INSERT INTO catalog_product(id,name,description,category,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                id,name,description,category,utc(now),utc(now));
    }
    @Override public void updateProduct(UUID id, String name, String description, String category, Instant now) {
        requireOne(jdbc.update("UPDATE catalog_product SET name=?,description=?,category=?,updated_at=? WHERE id=?",name,description,category,utc(now),id), "product");
    }
    @Override @Transactional public boolean activateProduct(UUID id, Instant now) {
        requireOne(jdbc.update("UPDATE catalog_product SET updated_at=? WHERE id=?", utc(now), id), "product");
        jdbc.query("SELECT id FROM catalog_product WHERE id=? FOR UPDATE", rs -> { }, id);
        Integer count=jdbc.queryForObject("SELECT count(*) FROM catalog_sku WHERE product_id=?",Integer.class,id);
        if (count == null || count < 1) return false;
        jdbc.update("UPDATE catalog_product SET is_active=TRUE,updated_at=? WHERE id=?",utc(now),id); return true;
    }
    @Override public void deactivateProduct(UUID id, Instant now) {
        requireOne(jdbc.update("UPDATE catalog_product SET is_active=FALSE, updated_at=? WHERE id=?",utc(now),id),"product");
        jdbc.update("UPDATE catalog_sku SET is_active=FALSE, updated_at=? WHERE product_id=?",utc(now),id);
    }
    @Override public void createSku(UUID id, UUID productId, String code, BigDecimal price, BigDecimal vat, Instant now) {
        jdbc.update("INSERT INTO catalog_sku(id,product_id,code,base_net_price,vat_rate,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",id,productId,code,price,vat,utc(now),utc(now));
    }
    @Override public void updateSku(UUID id, String code, Instant now) {
        requireOne(jdbc.update("UPDATE catalog_sku SET code=?,updated_at=? WHERE id=?",code,utc(now),id),"SKU");
    }
    @Override @Transactional public void setSkuActivity(UUID id, boolean active, Instant now) {
        if (active) {
            Boolean parent=jdbc.query("SELECT p.is_active FROM catalog_sku s JOIN catalog_product p ON p.id=s.product_id WHERE s.id=? FOR UPDATE OF p", rs -> rs.next() && rs.getBoolean(1),id);
            if (parent == null) throw new CatalogException("SKU not found");
            if (!parent) throw new CatalogException("SKU requires an active product");
        }
        requireOne(jdbc.update("UPDATE catalog_sku SET is_active=?,updated_at=? WHERE id=?",active,utc(now),id),"SKU");
    }
    @Override @Transactional public PriceVat setPriceAndVat(UUID id, BigDecimal price, BigDecimal vat, Instant now) {
        PriceVat previous = jdbc.query("SELECT base_net_price,vat_rate FROM catalog_sku WHERE id=? FOR UPDATE",
                rs -> { if (!rs.next()) throw new CatalogException("SKU not found"); return new PriceVat(rs.getBigDecimal(1),rs.getBigDecimal(2)); },id);
        requireOne(jdbc.update("UPDATE catalog_sku SET base_net_price=?,vat_rate=?,updated_at=? WHERE id=?",price,vat,utc(now),id),"SKU");
        return previous;
    }
    @Override public void assignAttribute(UUID skuId, UUID definitionId, UUID valueId, Instant now) {
        jdbc.update("""
            INSERT INTO catalog_sku_attribute_assignment(sku_id,attribute_definition_id,attribute_value_id,created_at)
            VALUES(?,?,?,?) ON CONFLICT(sku_id,attribute_definition_id) DO UPDATE
              SET attribute_value_id=EXCLUDED.attribute_value_id,created_at=EXCLUDED.created_at
            """,skuId,definitionId,valueId,utc(now));
    }
    @Override public void removeAttribute(UUID skuId, UUID definitionId) {
        jdbc.update("DELETE FROM catalog_sku_attribute_assignment WHERE sku_id=? AND attribute_definition_id=?",skuId,definitionId);
    }
    @Override public UUID createDefinition(String name, Instant now) {
        UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO catalog_attribute_definition(id,name,created_at,updated_at) VALUES(?,?,?,?)",id,name,utc(now),utc(now)); return id;
    }
    @Override public UUID createAttributeValue(UUID definitionId, String value, Instant now) {
        UUID id=UUID.randomUUID(); jdbc.update("INSERT INTO catalog_attribute_value(id,definition_id,value,created_at,updated_at) VALUES(?,?,?,?,?)",id,definitionId,value,utc(now),utc(now)); return id;
    }
    private static void requireOne(int count,String target) { if(count!=1) throw new CatalogException(target+" not found"); }
    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
