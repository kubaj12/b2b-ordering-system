package io.github.kubaj12.online_store.catalogpricing.persistence;

import io.github.kubaj12.online_store.catalogpricing.application.CatalogStore;
import io.github.kubaj12.online_store.catalogpricing.application.CustomerCatalogStore;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCustomerCatalogStore implements CustomerCatalogStore {
    private final JdbcTemplate jdbc;
    public JdbcCustomerCatalogStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public ProductPage page(String search, String category, int page, int size) {
        String term = search == null ? "" : search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String selectedCategory = category == null ? "" : category.trim();
        long total = jdbc.queryForObject("""
                SELECT count(*) FROM catalog_product p
                WHERE p.is_active AND (?='' OR p.category=?)
                  AND (?='' OR p.name ILIKE ? ESCAPE '\\' OR EXISTS (
                      SELECT 1 FROM catalog_sku s WHERE s.product_id=p.id AND s.is_active AND s.code ILIKE ? ESCAPE '\\'))
                  AND EXISTS (SELECT 1 FROM catalog_sku s WHERE s.product_id=p.id AND s.is_active)
                """, Long.class, selectedCategory, selectedCategory, term, "%"+term+"%", "%"+term+"%");
        List<UUID> ids = jdbc.query("""
                SELECT p.id FROM catalog_product p
                WHERE p.is_active AND (?='' OR p.category=?)
                  AND (?='' OR p.name ILIKE ? ESCAPE '\\' OR EXISTS (
                      SELECT 1 FROM catalog_sku s WHERE s.product_id=p.id AND s.is_active AND s.code ILIKE ? ESCAPE '\\'))
                  AND EXISTS (SELECT 1 FROM catalog_sku s WHERE s.product_id=p.id AND s.is_active)
                ORDER BY p.name,p.id LIMIT ? OFFSET ?
                """, (rs,n)->rs.getObject(1,UUID.class), selectedCategory, selectedCategory, term,
                "%"+term+"%", "%"+term+"%", size, (long)page*size);
        List<Product> products = products(ids);
        return new ProductPage(products,page,size,total);
    }
    @Override public List<String> categories() {
        return jdbc.query("SELECT DISTINCT p.category FROM catalog_product p WHERE p.is_active AND EXISTS (SELECT 1 FROM catalog_sku s WHERE s.product_id=p.id AND s.is_active) ORDER BY p.category",
                (rs,n)->rs.getString(1));
    }
    private List<Product> products(List<UUID> ids) {
        if(ids.isEmpty()) return List.of();
        String marks=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));
        var bases=jdbc.query("SELECT id,name,description,category FROM catalog_product WHERE is_active AND id IN ("+marks+") ORDER BY name,id",
                (rs,n)->new Product(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),List.of()),ids.toArray());
        var productIds=new ArrayList<UUID>(); bases.forEach(p->productIds.add(p.id()));
        if(productIds.isEmpty()) return List.of();
        String productMarks=String.join(",",java.util.Collections.nCopies(productIds.size(),"?"));
        var variantRows=jdbc.query("""
                SELECT s.id,s.product_id,s.code,s.available_quantity,(i.sku_id IS NOT NULL) image_present
                FROM catalog_sku s LEFT JOIN catalog_sku_image_metadata i ON i.sku_id=s.id
                WHERE s.product_id IN (%s) AND s.is_active ORDER BY s.product_id,s.code,s.id
                """.formatted(productMarks),(rs,n)->new VariantRow(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getInt(4),rs.getBoolean(5)),productIds.toArray());
        var skuIds=variantRows.stream().map(VariantRow::id).toList();
        Map<UUID,List<CatalogStore.VariantValue>> attributes=new LinkedHashMap<>();
        if(!skuIds.isEmpty()) {
            String skuMarks=String.join(",",java.util.Collections.nCopies(skuIds.size(),"?"));
            jdbc.query("""
                    SELECT a.sku_id,d.id,d.name,v.id,v.value FROM catalog_sku_attribute_assignment a
                    JOIN catalog_attribute_definition d ON d.id=a.attribute_definition_id
                    JOIN catalog_attribute_value v ON v.id=a.attribute_value_id
                    WHERE a.sku_id IN (%s) ORDER BY d.name
                    """.formatted(skuMarks),rs->{while(rs.next()) attributes.computeIfAbsent(rs.getObject(1,UUID.class),ignored->new ArrayList<>())
                    .add(new CatalogStore.VariantValue(rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,UUID.class),rs.getString(5)));},skuIds.toArray());
        }
        Map<UUID,List<Variant>> byProduct=new LinkedHashMap<>();
        for(var row:variantRows) byProduct.computeIfAbsent(row.productId(),ignored->new ArrayList<>())
                .add(new Variant(row.id(),row.code(),row.quantity(),row.imagePresent(),attributes.getOrDefault(row.id(),List.of())));
        return bases.stream().map(p->new Product(p.id(),p.name(),p.description(),p.category(),byProduct.getOrDefault(p.id(),List.of()))).toList();
    }
    private record VariantRow(UUID id,UUID productId,String code,int quantity,boolean imagePresent) { }
}
