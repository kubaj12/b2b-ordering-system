package io.github.kubaj12.online_store.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.inventory.application.InventoryService;
import io.github.kubaj12.online_store.shared.web.error.WebErrorException;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

class InventoryVerificationIntegrationTests extends PostgreSqlServiceTestSupport {
    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");
    private static final UUID ACTOR = UUID.fromString("01998e62-e700-7000-8000-000000000001");
    private static final UUID SECOND_ACTOR = UUID.fromString("01998e62-e700-7000-8000-000000000002");
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryService inventory;
    @Autowired TransactionTemplate transactions;
    private UUID product;
    private UUID[] skus;

    @BeforeEach void fixtures() {
        testClock().set(NOW);
        new IdentityDatabaseFixture(jdbc).insertActiveUser(ACTOR, "inventory.employee@example.test", "EMPLOYEE", NOW);
        new IdentityDatabaseFixture(jdbc).insertActiveUser(SECOND_ACTOR, "inventory.second@example.test", "EMPLOYEE", NOW);
        product=UUID.randomUUID(); skus=new UUID[22];
        jdbc.update("INSERT INTO catalog_product(id,name,category,is_active,created_at,updated_at) VALUES(?,?,?,TRUE,?,?)",
                product,"Wkręty nierdzewne","Mocowania",at(NOW),at(NOW));
        for(int i=0;i<skus.length;i++) {
            skus[i]=UUID.randomUUID();
            jdbc.update("INSERT INTO catalog_sku(id,product_id,code,base_net_price,vat_rate,is_active,available_quantity,created_at,updated_at) VALUES(?,?,?,?,?,TRUE,?,?,?)",
                    skus[i],product,String.format("FIX-%02d",i),10.00,23.00,i,at(NOW),at(NOW));
        }
        UUID other=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_product(id,name,category,is_active,created_at,updated_at) VALUES(?,?,?,TRUE,?,?)",
                other,"Podkładki","Akcesoria",at(NOW),at(NOW));
        jdbc.update("INSERT INTO catalog_sku(id,product_id,code,base_net_price,vat_rate,is_active,available_quantity,created_at,updated_at) VALUES(?,?,?,?,?,TRUE,?,?,?)",
                UUID.randomUUID(),other,"WASHER-1",10.00,23.00,4,at(NOW),at(NOW));
    }

    @Test void filtersSearchAndCategoryAndKeepsPaginationWithinResultBoundaries() {
        var first=inventory.page("FIX", "Mocowania",0);
        assertThat(first.totalElements()).isEqualTo(22);
        assertThat(first.items()).hasSize(20);
        assertThat(first.items()).extracting("skuCode").contains("FIX-00").doesNotContain("WASHER-1");
        var second=inventory.page("FIX", "Mocowania",1);
        assertThat(second.items()).hasSize(2);
        assertThat(inventory.page("FIX", "Mocowania",99).number()).isEqualTo(1);
        assertThat(inventory.page("missing", "Mocowania",0).totalElements()).isZero();
    }

    @Test void validatesIntegerAndBoundaryInputsAndRecordsOnlyRealChanges() {
        assertThatThrownBy(()->inventory.page("x".repeat(121),"",0)).isInstanceOf(WebErrorException.class);
        assertThatThrownBy(()->inventory.page("","",-1)).isInstanceOf(WebErrorException.class);
        assertThatThrownBy(()->inventory.page("","",1_000_001)).isInstanceOf(WebErrorException.class);
        assertThatThrownBy(()->inventory.update(skus[0],-1,0,ACTOR)).isInstanceOf(WebErrorException.class);
        inventory.update(skus[0],Integer.MAX_VALUE,0,ACTOR);
        inventory.update(skus[0],Integer.MAX_VALUE,1,ACTOR); // valid no-op advances editor version, not history
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_change WHERE sku_id=?",Integer.class,skus[0])).isEqualTo(1);
        var row=jdbc.queryForMap("SELECT previous_quantity,new_quantity,acting_user_id,inventory_version,changed_at FROM inventory_change WHERE sku_id=?",skus[0]);
        assertThat(row).containsEntry("previous_quantity",0).containsEntry("new_quantity",Integer.MAX_VALUE)
                .containsEntry("acting_user_id",ACTOR).containsEntry("inventory_version",1L);
        assertThat(((java.sql.Timestamp)row.get("changed_at")).toInstant()).isEqualTo(NOW);
        var audit=jdbc.queryForMap("""
                SELECT event_type,target_id,acting_user_id,
                       change_metadata #>> '{quantity,from}' old_quantity,
                       change_metadata #>> '{quantity,to}' new_quantity
                FROM audit_event WHERE target_id=?
                """,skus[0].toString());
        assertThat(audit).containsEntry("event_type","inventory.quantity.changed")
                .containsEntry("target_id",skus[0].toString()).containsEntry("acting_user_id",ACTOR)
                .containsEntry("old_quantity","0").containsEntry("new_quantity",String.valueOf(Integer.MAX_VALUE));
        assertThat(inventory.page("FIX-00","Mocowania",0).items().getFirst())
                .satisfies(item->{assertThat(item.quantity()).isEqualTo(Integer.MAX_VALUE); assertThat(item.changedBy()).isEqualTo("inventory.employee@example.test"); assertThat(item.changedAt()).isEqualTo(NOW);});
    }

    @Test void secondEditorCannotOverwriteEvenAfterQuantityReturnsToOriginalValue() {
        long version=inventory.page("FIX-00","Mocowania",0).items().getFirst().version();
        inventory.update(skus[0],7,version,ACTOR);
        inventory.update(skus[0],0,version+1,ACTOR);
        assertThatThrownBy(()->inventory.update(skus[0],9,version,ACTOR))
                .isInstanceOf(WebErrorException.class);
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM catalog_sku WHERE id=?",Integer.class,skus[0])).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_change WHERE sku_id=?",Integer.class,skus[0])).isEqualTo(2);
    }

    @Test void concurrentEditorsUsingTheSameObservedVersionSerializeAndOneConflicts() throws Exception {
        long observedVersion=inventory.page("FIX-00","Mocowania",0).items().getFirst().version();
        CountDownLatch firstOwnsSkuLock=new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction=new CountDownLatch(1);
        CountDownLatch secondStartingWrite=new CountDownLatch(1);
        ExecutorService editors=Executors.newFixedThreadPool(2);
        try {
            var first=editors.submit(()->transactions.execute(status->{
                jdbc.queryForObject("SELECT inventory_version FROM catalog_sku WHERE id=? FOR UPDATE",Long.class,skus[0]);
                firstOwnsSkuLock.countDown();
                inventory.update(skus[0],7,observedVersion,ACTOR);
                await(releaseFirstTransaction);
                return true;
            }));
            assertThat(firstOwnsSkuLock.await(5,TimeUnit.SECONDS)).isTrue();
            var second=editors.submit(()->{
                secondStartingWrite.countDown();
                try {
                    return transactions.execute(status->{inventory.update(skus[0],9,observedVersion,SECOND_ACTOR); return true;});
                } catch(WebErrorException conflict) { return false; }
            });
            assertThat(secondStartingWrite.await(5,TimeUnit.SECONDS)).isTrue();
            awaitSkuLockWait();
            releaseFirstTransaction.countDown();
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5,TimeUnit.SECONDS)).isFalse();
            assertThat(jdbc.queryForObject("SELECT available_quantity FROM catalog_sku WHERE id=?",Integer.class,skus[0])).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT inventory_version FROM catalog_sku WHERE id=?",Long.class,skus[0])).isEqualTo(observedVersion+1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_change WHERE sku_id=?",Integer.class,skus[0])).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE target_id=?",Integer.class,skus[0].toString())).isEqualTo(1);
        } finally {
            releaseFirstTransaction.countDown();
            editors.shutdownNow();
        }
    }

    private void awaitSkuLockWait() throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            Integer waiting=jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type='Lock' AND query ILIKE '%catalog_sku%FOR UPDATE%'
                    """,Integer.class);
            if(waiting!=null && waiting>0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("second inventory editor did not wait for the SKU row lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if(!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for editor interleaving");
        } catch(InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("editor interleaving interrupted",exception);
        }
    }

    private static OffsetDateTime at(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
