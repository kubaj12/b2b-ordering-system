package io.github.kubaj12.online_store.catalogpricing.persistence;

import io.github.kubaj12.online_store.catalogpricing.application.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPriceListStore implements PriceListStore {
    private final JdbcTemplate jdbc;
    public JdbcPriceListStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static OffsetDateTime utc(Instant i) { return i.atOffset(ZoneOffset.UTC); }
    @Override public List<PriceList> lists() {
        return jdbc.query("SELECT l.id,l.name,count(i.sku_id) FROM catalog_price_list l LEFT JOIN catalog_price_list_item i ON i.price_list_id=l.id GROUP BY l.id ORDER BY l.name,l.id",(rs,n)->new PriceList(rs.getObject(1,UUID.class),rs.getString(2),rs.getInt(3),List.of()));
    }
    private List<Item> items(UUID id) { return jdbc.query("SELECT s.id,s.code,p.name,i.net_price FROM catalog_price_list_item i JOIN catalog_sku s ON s.id=i.sku_id JOIN catalog_product p ON p.id=s.product_id WHERE i.price_list_id=? ORDER BY p.name,s.code",(rs,n)->new Item(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getBigDecimal(4)),id); }
    @Override public List<Sku> skus() { return jdbc.query("SELECT s.id,s.code,p.name,s.base_net_price FROM catalog_sku s JOIN catalog_product p ON p.id=s.product_id ORDER BY p.name,s.code",(r,n)->new Sku(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getBigDecimal(4))); }
    @Override public UUID skuIdByCode(String code) { return jdbc.query("SELECT id FROM catalog_sku WHERE code=?",r->r.next()?r.getObject(1,UUID.class):null,code); }
    @Override public List<Customer> customers() { return jdbc.query("SELECT c.user_id,u.email,c.company_name,a.price_list_id,l.name FROM customer_profile c JOIN identity_user u ON u.id=c.user_id LEFT JOIN catalog_customer_price_list_assignment a ON a.customer_id=c.user_id LEFT JOIN catalog_price_list l ON l.id=a.price_list_id ORDER BY c.company_name,c.user_id",(r,n)->new Customer(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getObject(4,UUID.class),r.getString(5))); }
    @Override public CustomerPricePage customerPrices(UUID customerId,int page,int pageSize) {
        Integer count=jdbc.queryForObject("SELECT count(*) FROM catalog_customer_specific_price WHERE customer_id=?",Integer.class,customerId);
        int total=count==null?0:(count+pageSize-1)/pageSize;int safePage=total==0?0:Math.min(page,total-1);
        List<CustomerPrice> rows=jdbc.query("SELECT x.customer_id,x.sku_id,s.code,p.name,x.net_price FROM catalog_customer_specific_price x JOIN catalog_sku s ON s.id=x.sku_id JOIN catalog_product p ON p.id=s.product_id WHERE x.customer_id=? ORDER BY p.name,s.code LIMIT ? OFFSET ?",(r,n)->new CustomerPrice(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getBigDecimal(5)),customerId,pageSize,safePage*pageSize);
        return new CustomerPricePage(rows,safePage,total);
    }
    @Override public PriceList list(UUID id) { return jdbc.query("SELECT id,name FROM catalog_price_list WHERE id=?",r->r.next()?new PriceList(r.getObject(1,UUID.class),r.getString(2),0,items(id)):null,id); }
    @Override public boolean priceListExists(UUID id) { Boolean exists=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM catalog_price_list WHERE id=?)",Boolean.class,id);return Boolean.TRUE.equals(exists); }
    @Override public BigDecimal listPrice(UUID list,UUID sku) { return jdbc.query("SELECT net_price FROM catalog_price_list_item WHERE price_list_id=? AND sku_id=?",r->r.next()?r.getBigDecimal(1):null,list,sku); }
    @Override public BigDecimal customerPrice(UUID customer,UUID sku) { return jdbc.query("SELECT net_price FROM catalog_customer_specific_price WHERE customer_id=? AND sku_id=?",r->r.next()?r.getBigDecimal(1):null,customer,sku); }
    @Override public PriceCandidates priceCandidates(UUID customer, UUID sku) {
        return jdbc.query("""
                SELECT cp.net_price AS customer_price, lp.net_price AS list_price,
                       s.base_net_price, s.vat_rate
                FROM catalog_sku s
                JOIN customer_profile c ON c.user_id=?
                LEFT JOIN catalog_customer_specific_price cp
                  ON cp.customer_id=? AND cp.sku_id=s.id
                LEFT JOIN catalog_customer_price_list_assignment a ON a.customer_id=?
                LEFT JOIN catalog_price_list_item lp
                  ON lp.price_list_id=a.price_list_id AND lp.sku_id=s.id
                WHERE s.id=?
                """, r -> {
            if (!r.next()) return null;
            return new PriceCandidates(r.getBigDecimal("customer_price"), r.getBigDecimal("list_price"),
                    r.getBigDecimal("base_net_price"), r.getBigDecimal("vat_rate"));
        }, customer, customer, customer, sku);
    }
    @Override public Map<UUID, PriceCandidates> priceCandidates(UUID customer, List<UUID> skus) {
        if (skus.isEmpty()) return Map.of();
        String marks=String.join(",",Collections.nCopies(skus.size(),"?"));
        var args=new ArrayList<Object>(); args.add(customer); args.add(customer); args.add(customer); args.addAll(skus);
        List<Map.Entry<UUID,PriceCandidates>> rows=jdbc.query("""
                SELECT s.id,cp.net_price AS customer_price,lp.net_price AS list_price,s.base_net_price,s.vat_rate
                FROM catalog_sku s JOIN customer_profile c ON c.user_id=?
                LEFT JOIN catalog_customer_specific_price cp ON cp.customer_id=? AND cp.sku_id=s.id
                LEFT JOIN catalog_customer_price_list_assignment a ON a.customer_id=?
                LEFT JOIN catalog_price_list_item lp ON lp.price_list_id=a.price_list_id AND lp.sku_id=s.id
                WHERE s.id IN (%s)
                """.formatted(marks),(r,n)->Map.entry(r.getObject("id",UUID.class),new PriceCandidates(
                        r.getBigDecimal("customer_price"),r.getBigDecimal("list_price"),r.getBigDecimal("base_net_price"),r.getBigDecimal("vat_rate"))),args.toArray());
        var result=new LinkedHashMap<UUID,PriceCandidates>(); rows.forEach(row->result.put(row.getKey(),row.getValue()));
        return result;
    }
    @Override public void lockSku(UUID sku) {
        List<UUID> rows=jdbc.query("SELECT id FROM catalog_sku WHERE id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),sku);
        if(rows.isEmpty())throw new CatalogException("SKU not found");
    }
    @Override public void lockCustomer(UUID customer) {
        List<UUID> rows=jdbc.query("SELECT user_id FROM customer_profile WHERE user_id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),customer);
        if(rows.isEmpty())throw new CatalogException("customer not found");
    }
    @Override public void createList(UUID id,String name,Instant now) { jdbc.update("INSERT INTO catalog_price_list(id,name,created_at,updated_at) VALUES(?,?,?,?)",id,name,utc(now),utc(now)); }
    @Override public String renameList(UUID id,String name,Instant now) { return jdbc.query("UPDATE catalog_price_list SET name=?,updated_at=? WHERE id=? RETURNING name",r->r.next()?r.getString(1):null,name,utc(now),id); }
    @Override public void saveListPrice(UUID list,UUID sku,BigDecimal price,Instant now) { jdbc.update("INSERT INTO catalog_price_list_item(price_list_id,sku_id,net_price,created_at,updated_at) VALUES(?,?,?, ?,?) ON CONFLICT(price_list_id,sku_id) DO UPDATE SET net_price=EXCLUDED.net_price,updated_at=EXCLUDED.updated_at",list,sku,price,utc(now),utc(now)); }
    @Override public void removeListPrice(UUID list,UUID sku) { jdbc.update("DELETE FROM catalog_price_list_item WHERE price_list_id=? AND sku_id=?",list,sku); }
    @Override public UUID assignedList(UUID customer) { return jdbc.query("SELECT price_list_id FROM catalog_customer_price_list_assignment WHERE customer_id=?",r->r.next()?r.getObject(1,UUID.class):null,customer); }
    @Override public void assignList(UUID customer,UUID list,Instant now) { jdbc.update("INSERT INTO catalog_customer_price_list_assignment(customer_id,price_list_id,created_at,updated_at) VALUES(?,?,?,?) ON CONFLICT(customer_id) DO UPDATE SET price_list_id=EXCLUDED.price_list_id,updated_at=EXCLUDED.updated_at",customer,list,utc(now),utc(now)); }
    @Override public void unassignList(UUID customer) { jdbc.update("DELETE FROM catalog_customer_price_list_assignment WHERE customer_id=?",customer); }
    @Override public void saveCustomerPrice(UUID customer,UUID sku,BigDecimal price,Instant now) { jdbc.update("INSERT INTO catalog_customer_specific_price(customer_id,sku_id,net_price,created_at,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(customer_id,sku_id) DO UPDATE SET net_price=EXCLUDED.net_price,updated_at=EXCLUDED.updated_at",customer,sku,price,utc(now),utc(now)); }
    @Override public void removeCustomerPrice(UUID customer,UUID sku) { jdbc.update("DELETE FROM catalog_customer_specific_price WHERE customer_id=? AND sku_id=?",customer,sku); }
}
