package io.github.kubaj12.online_store.inventory.persistence;

import java.util.List;
import java.util.UUID;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import io.github.kubaj12.online_store.inventory.application.InventoryStore;

@Repository
public class JdbcInventoryStore implements InventoryStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public JdbcInventoryStore(JdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }

    @Override public Page page(String search,String category,int page,int size) {
        String term=escape(search), selected=category==null?"":category.trim();
        Object[] args={selected,selected,term,"%"+term+"%","%"+term+"%"};
        long total=jdbc.queryForObject("""
                SELECT count(*) FROM catalog_sku s JOIN catalog_product p ON p.id=s.product_id
                WHERE (?='' OR p.category=?) AND (?='' OR p.name ILIKE ? ESCAPE '\\' OR s.code ILIKE ? ESCAPE '\\')
                """,Long.class,args);
        var rows=jdbc.query("""
                SELECT s.id,p.name,s.code,p.category,s.available_quantity,s.inventory_version,
                       u.email, h.changed_at
                FROM catalog_sku s JOIN catalog_product p ON p.id=s.product_id
                LEFT JOIN LATERAL (SELECT acting_user_id,changed_at FROM inventory_change
                    WHERE sku_id=s.id ORDER BY inventory_version DESC,changed_at DESC,id DESC LIMIT 1) h ON true
                LEFT JOIN identity_user u ON u.id=h.acting_user_id
                WHERE (?='' OR p.category=?) AND (?='' OR p.name ILIKE ? ESCAPE '\\' OR s.code ILIKE ? ESCAPE '\\')
                ORDER BY p.name,s.code,s.id LIMIT ? OFFSET ?
                """,(rs,n)->new Item(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getInt(5),rs.getLong(6),rs.getString(7),rs.getTimestamp(8)==null?null:rs.getTimestamp(8).toInstant()),
                selected,selected,term,"%"+term+"%","%"+term+"%",size,(long)page*size);
        return new Page(rows,page,size,total);
    }
    @Override public List<String> categories() {
        return jdbc.query("SELECT DISTINCT category FROM catalog_product ORDER BY category",(rs,n)->rs.getString(1));
    }
    @Override @Transactional public UpdateResult update(UUID skuId,int quantity,long expectedVersion,UUID actorId) {
        var current=jdbc.query("SELECT available_quantity,inventory_version FROM catalog_sku WHERE id=? FOR UPDATE",
                (rs,n)->new long[]{rs.getInt(1),rs.getLong(2)},skuId);
        if(current.isEmpty()) return null;
        int previous=(int)current.getFirst()[0]; long actualVersion=current.getFirst()[1];
        if(actualVersion!=expectedVersion) return new UpdateResult(false,previous);
        long nextVersion=actualVersion+1;
        jdbc.update("UPDATE catalog_sku SET available_quantity=?,inventory_version=?,updated_at=? WHERE id=?",
                quantity,nextVersion,OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),
                skuId);
        if(previous!=quantity) jdbc.update("""
                INSERT INTO inventory_change(id,sku_id,previous_quantity,new_quantity,acting_user_id,inventory_version,changed_at)
                VALUES (?,?,?,?,?,?,?)
                """,UUID.randomUUID(),skuId,previous,quantity,actorId,nextVersion,
                OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        return new UpdateResult(true,previous);
    }
    private static String escape(String value) { return value==null?"":value.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_"); }
}
