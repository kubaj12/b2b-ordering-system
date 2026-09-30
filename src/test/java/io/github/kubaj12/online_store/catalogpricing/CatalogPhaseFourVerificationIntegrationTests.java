package io.github.kubaj12.online_store.catalogpricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import io.github.kubaj12.online_store.catalogpricing.application.CatalogAdministrationService;
import io.github.kubaj12.online_store.catalogpricing.application.CatalogException;
import io.github.kubaj12.online_store.catalogpricing.application.CustomerCatalogService;
import io.github.kubaj12.online_store.catalogpricing.application.EffectivePriceResolver;
import io.github.kubaj12.online_store.catalogpricing.application.ImageReference;
import io.github.kubaj12.online_store.catalogpricing.application.PriceListAdministrationService;
import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;
import io.github.kubaj12.online_store.shared.money.DecimalPriceCalculator;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

/** Regression coverage for the Phase 4 catalog, pricing, visibility, and image guarantees. */
class CatalogPhaseFourVerificationIntegrationTests extends PostgreSqlServiceTestSupport {
    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final UUID ACTOR = UUID.fromString("01998e62-e700-7000-8000-000000000001");

    @Autowired JdbcTemplate jdbc;
    @Autowired CatalogAdministrationService catalog;
    @Autowired PriceListAdministrationService priceLists;
    @Autowired CustomerCatalogService customerCatalog;
    @Autowired EffectivePriceResolver resolver;

    private UUID customerA;
    private UUID customerB;
    private UUID product;
    private UUID sku;

    @BeforeEach
    void fixture() {
        testClock().set(NOW);
        var users = new IdentityDatabaseFixture(jdbc);
        users.insertActiveUser(ACTOR, "catalog.staff@example.test", "EMPLOYEE", NOW);
        customerA = UUID.randomUUID(); customerB = UUID.randomUUID();
        users.insertActiveUser(customerA, "a@example.test", "CUSTOMER", NOW);
        users.insertActiveUser(customerB, "b@example.test", "CUSTOMER", NOW);
        insertProfile(customerA, "1111111111"); insertProfile(customerB, "2222222222");
        product = UUID.randomUUID(); sku = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_product(id,name,description,category,is_active,created_at,updated_at) VALUES(?,?,?,?,TRUE,?,?)",
                product, "Aktywny produkt", "Opis produktu", "Akcesoria", at(NOW), at(NOW));
        jdbc.update("INSERT INTO catalog_sku(id,product_id,code,base_net_price,vat_rate,available_quantity,is_active,created_at,updated_at) VALUES(?,?,?,?,?,0,TRUE,?,?)",
                sku, product, "SKU-A", new BigDecimal("10.00"), new BigDecimal("23.00"), at(NOW), at(NOW));
    }

    @Test
    void databaseConstraintsProtectAttributeUniquenessCurrencyPriceAndStock() {
        UUID definition = UUID.randomUUID(), valueA = UUID.randomUUID(), valueB = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_attribute_definition(id,name,created_at,updated_at) VALUES(?,?,?,?)", definition,"Color",at(NOW),at(NOW));
        jdbc.update("INSERT INTO catalog_attribute_value(id,definition_id,value,created_at,updated_at) VALUES(?,?,?,?,?),(?,?,?,?,?)",
                valueA,definition,"Czerwony",at(NOW),at(NOW), valueB,definition,"Niebieski",at(NOW),at(NOW));
        jdbc.update("INSERT INTO catalog_sku_attribute_assignment(sku_id,attribute_definition_id,attribute_value_id,created_at) VALUES(?,?,?,?)",sku,definition,valueA,at(NOW));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO catalog_sku_attribute_assignment(sku_id,attribute_definition_id,attribute_value_id,created_at) VALUES(?,?,?,?)",sku,definition,valueB,at(NOW)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE catalog_sku SET available_quantity=-1 WHERE id=?",sku)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE catalog_sku SET base_currency='USD' WHERE id=?",sku)).isInstanceOf(RuntimeException.class);
        UUID list=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_price_list(id,name,created_at,updated_at) VALUES(?,?,?,?)",list,"USD test",at(NOW),at(NOW));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO catalog_price_list_item(price_list_id,sku_id,net_price,currency,created_at,updated_at) VALUES(?,?,?,'USD',?,?)",list,sku,new BigDecimal("1.00"),at(NOW),at(NOW))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO catalog_customer_specific_price(customer_id,sku_id,net_price,currency,created_at,updated_at) VALUES(?,?,?,'USD',?,?)",customerA,sku,new BigDecimal("1.00"),at(NOW),at(NOW))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> catalog.changeBasePriceAndVat(sku,new BigDecimal("1.001"),new BigDecimal("23.00"),ACTOR)).isInstanceOf(CatalogException.class);
        assertThatThrownBy(() -> catalog.changeBasePriceAndVat(sku,new BigDecimal("1.00"),new BigDecimal("8.001"),ACTOR)).isInstanceOf(CatalogException.class);
        UUID otherDefinition=UUID.randomUUID(), otherValue=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_attribute_definition(id,name,created_at,updated_at) VALUES(?,?,?,?)",otherDefinition,"Size",at(NOW),at(NOW));
        jdbc.update("INSERT INTO catalog_attribute_value(id,definition_id,value,created_at,updated_at) VALUES(?,?,?,?,?)",otherValue,otherDefinition,"L",at(NOW),at(NOW));
        assertThatThrownBy(() -> catalog.assignVariantAttribute(sku,definition,otherValue)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void pricePrecedenceIsCustomerScopedAndEveryPriceMutationHasExactAuditData() {
        UUID color=catalog.createAttributeDefinition("Kolor");
        UUID blue=catalog.createAttributeValue(color,"Niebieski");
        catalog.assignVariantAttribute(sku,color,blue);
        UUID list = priceLists.create("Lista A");
        priceLists.price(list,sku,new BigDecimal("8.00"),ACTOR);
        priceLists.assign(customerA,list,ACTOR);
        authenticate(customerA);
        assertThat(resolver.resolve(sku).unitNetPrice()).isEqualByComparingTo("8.00");
        assertCustomerPagePrice("8.00","1.84","9.84");
        assertBrowsedAttribute(color,"Kolor",blue,"Niebieski");
        authenticate(customerB);
        assertThat(resolver.resolve(sku).unitNetPrice()).isEqualByComparingTo("10.00");
        assertCustomerPagePrice("10.00","2.30","12.30");
        assertBrowsedAttribute(color,"Kolor",blue,"Niebieski");
        priceLists.customerPrice(customerA,sku,new BigDecimal("7.00"),ACTOR);
        authenticate(customerA);
        assertThat(resolver.resolve(sku).unitNetPrice()).isEqualByComparingTo("7.00");
        assertCustomerPagePrice("7.00","1.61","8.61");
        assertBrowsedAttribute(color,"Kolor",blue,"Niebieski");
        priceLists.removeCustomerPrice(customerA,sku,ACTOR);
        assertThat(resolver.resolve(sku).unitNetPrice()).isEqualByComparingTo("8.00");
        priceLists.removePrice(list,sku,ACTOR);
        assertThat(resolver.resolve(sku).unitNetPrice()).isEqualByComparingTo("10.00");
        priceLists.unassign(customerA,ACTOR);
        assertThat(resolver.resolve(sku).unitNetPrice()).isEqualByComparingTo("10.00");

        assertAudit("catalog.price_list_price.changed",sku,"netPrice","from",null,"to","8.00", "priceListId",list.toString());
        assertAudit("catalog.customer_price_list.assignment_changed",customerA,"priceListId","from",null,"to",list.toString(), null,null);
        assertAudit("catalog.customer_price.changed",sku,"netPrice","from",null,"to","7.00", "customerId",customerA.toString());
        assertAudit("catalog.customer_price.changed",sku,"netPrice","from","7.00","to",null, "customerId",customerA.toString());
        assertAudit("catalog.price_list_price.changed",sku,"netPrice","from","8.00","to",null, "priceListId",list.toString());
        assertAudit("catalog.customer_price_list.assignment_changed",customerA,"priceListId","from",list.toString(),"to",null, null,null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE acting_user_id=?",Integer.class,ACTOR)).isEqualTo(6);

        catalog.changeBasePriceAndVat(sku,new BigDecimal("11.25"),new BigDecimal("8.00"),ACTOR);
        var baseAudit=jdbc.queryForMap("SELECT acting_user_id,event_type,occurred_at,change_metadata #>> '{baseNetPrice,from}' old_price,change_metadata #>> '{baseNetPrice,to}' new_price,change_metadata #>> '{vatRate,from}' old_vat,change_metadata #>> '{vatRate,to}' new_vat FROM audit_event WHERE target_id=? AND event_type='catalog.sku.pricing_changed'",sku.toString());
        assertThat(baseAudit).containsEntry("acting_user_id",ACTOR).containsEntry("event_type","catalog.sku.pricing_changed")
                .containsEntry("old_price","10.00").containsEntry("new_price","11.25").containsEntry("old_vat","23.00").containsEntry("new_vat","8.00");
        assertThat(((java.sql.Timestamp)baseAudit.get("occurred_at")).toInstant()).isEqualTo(NOW);
    }

    private void assertCustomerPagePrice(String net,String vat,String gross) {
        var row=customerCatalog.page("SKU-A","Akcesoria",0).products().getFirst().variants().getFirst();
        assertThat(row.price().unitNetPrice()).isEqualByComparingTo(net);
        assertThat(row.price().vatRate()).isEqualByComparingTo("23.00");
        assertThat(row.amounts().net()).isEqualByComparingTo(net);
        assertThat(row.amounts().vat()).isEqualByComparingTo(vat);
        assertThat(row.amounts().gross()).isEqualByComparingTo(gross);
    }

    private void assertBrowsedAttribute(UUID definitionId,String definitionName,UUID valueId,String value) {
        var attributes=customerCatalog.page("SKU-A","Akcesoria",0).products().getFirst().variants().getFirst().variant().attributes();
        assertThat(attributes).anySatisfy(attribute -> {
            assertThat(attribute.definitionId()).isEqualTo(definitionId);
            assertThat(attribute.definitionName()).isEqualTo(definitionName);
            assertThat(attribute.valueId()).isEqualTo(valueId);
            assertThat(attribute.value()).isEqualTo(value);
        });
    }

    private void assertAudit(String event,UUID target,String field,String fromKey,String from,String toKey,String to,String otherField,String otherValue) {
        String sql="SELECT acting_user_id,occurred_at,change_metadata #>> '{"+field+","+fromKey+"}' old_value,change_metadata #>> '{"+field+","+toKey+"}' new_value";
        if(otherField!=null) sql+=",change_metadata #>> '{"+otherField+",to}' other_value";
        sql+=" FROM audit_event WHERE event_type=? AND target_id=? AND change_metadata #>> '{"+field+","+fromKey+"}' IS NOT DISTINCT FROM ? AND change_metadata #>> '{"+field+","+toKey+"}' IS NOT DISTINCT FROM ?";
        var row=jdbc.queryForMap(sql,event,target.toString(),from,to);
        assertThat(row).containsEntry("acting_user_id",ACTOR).containsEntry("old_value",from).containsEntry("new_value",to);
        assertThat(((java.sql.Timestamp)row.get("occurred_at")).toInstant()).isEqualTo(NOW);
        if(otherField!=null) assertThat(row.get("other_value")).isEqualTo(otherValue);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE event_type=? AND target_id=? AND change_metadata #>> '{"+field+","+fromKey+"}' IS NOT DISTINCT FROM ? AND change_metadata #>> '{"+field+","+toKey+"}' IS NOT DISTINCT FROM ?",Integer.class,event,target.toString(),from,to)).isEqualTo(1);
    }

    @Test
    void browserSearchCategoryPagingAndUnavailableVisibilityUseActiveRowsOnly() {
        UUID inactiveProduct=UUID.randomUUID(), inactiveSku=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_product(id,name,category,is_active,created_at,updated_at) VALUES(?,?,?,FALSE,?,?)",inactiveProduct,"Ukryty","Akcesoria",at(NOW),at(NOW));
        jdbc.update("INSERT INTO catalog_sku(id,product_id,code,base_net_price,vat_rate,is_active,created_at,updated_at) VALUES(?,?,?,?,?,TRUE,?,?)",inactiveSku,inactiveProduct,"HIDDEN",new BigDecimal("1.00"),new BigDecimal("23.00"),at(NOW),at(NOW));
        UUID inactiveVariant=UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_sku(id,product_id,code,base_net_price,vat_rate,is_active,created_at,updated_at) VALUES(?,?,?,?,?,FALSE,?,?)",inactiveVariant,product,"SKU-INACTIVE",new BigDecimal("1.00"),new BigDecimal("23.00"),at(NOW),at(NOW));
        authenticate(customerA);
        var all=customerCatalog.page("", "Akcesoria", 0);
        assertThat(all.totalElements()).isEqualTo(1);
        assertThat(all.products().getFirst().variants().getFirst().variant().quantity()).isZero();
        assertThat(all.products().getFirst().variants()).hasSize(1);
        jdbc.update("UPDATE catalog_sku SET available_quantity=5 WHERE id=?",sku);
        assertThat(customerCatalog.page("SKU-A", "Akcesoria", 0).products().getFirst().variants().getFirst().variant().quantity()).isEqualTo(5);
        assertThat(customerCatalog.page("SKU-A", "", 0).totalElements()).isEqualTo(1);
        assertThat(customerCatalog.page("HIDDEN", "", 0).totalElements()).isZero();
        assertThat(customerCatalog.page("missing", "", 0).products()).isEmpty();
        for (int i=0;i<13;i++) {
            UUID p=UUID.randomUUID(), s=UUID.randomUUID();
            jdbc.update("INSERT INTO catalog_product(id,name,category,is_active,created_at,updated_at) VALUES(?,?,?,TRUE,?,?)",p,"Product %02d".formatted(i),"Other",at(NOW),at(NOW));
            jdbc.update("INSERT INTO catalog_sku(id,product_id,code,base_net_price,vat_rate,is_active,created_at,updated_at) VALUES(?,?,?,?,?,TRUE,?,?)",s,p,"PAGE-%02d".formatted(i),new BigDecimal("1.00"),new BigDecimal("0.00"),at(NOW),at(NOW));
        }
        var page0=customerCatalog.page("", "Other", 0); var page1=customerCatalog.page("", "Other", 1);
        assertThat(page0.totalElements()).isEqualTo(13); assertThat(page0.products()).hasSize(12);
        assertThat(page1.products()).hasSize(1); assertThat(customerCatalog.page("", "Other", 99).number()).isEqualTo(1);
    }

    @Test
    void imageUploadReplacementAndRemovalKeepOpaqueStorageLifecycleConsistent() throws Exception {
        byte[] png=png(4,3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_sku WHERE id=?",Integer.class,sku)).isEqualTo(1);
        catalog.uploadImage(sku,png);
        var metadata=jdbc.queryForMap("SELECT storage_key,content_type,byte_size,width,height FROM catalog_sku_image_metadata WHERE sku_id=?",sku);
        assertThat(metadata.get("content_type")).isEqualTo("image/png");
        assertThat(metadata.get("storage_key").toString()).doesNotContain("/").doesNotContain("..");
        assertThat(catalog.image(sku,false).bytes()).isNotEmpty();
        var thumbnail=catalog.image(sku,true);
        assertThat(thumbnail.contentType()).isEqualTo("image/jpeg");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(thumbnail.bytes())).getWidth()).isEqualTo(4);
        var firstKey=new ImageReference((String)metadata.get("storage_key"));
        assertThat(imageStorage().storedImages()).containsKey(firstKey);
        imageStorage().failNextDelete(new IllegalStateException("simulated storage outage"));
        catalog.uploadImage(sku,png(2,2));
        var replacement=jdbc.queryForMap("SELECT storage_key FROM catalog_sku_image_metadata WHERE sku_id=?",sku);
        var secondKey=new ImageReference((String)replacement.get("storage_key"));
        assertThat(secondKey).isNotEqualTo(firstKey);
        assertThat(imageStorage().storedImages()).containsKeys(firstKey,secondKey);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_image_cleanup WHERE storage_key=?",Integer.class,firstKey.value())).isEqualTo(1);
        catalog.retryImageCleanup();
        assertThat(imageStorage().storedImages()).containsKey(secondKey).doesNotContainKey(firstKey);
        imageStorage().failNextDelete(new IllegalStateException("simulated storage outage"));
        catalog.removeImage(sku);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_sku_image_metadata WHERE sku_id=?",Integer.class,sku)).isZero();
        assertThat(imageStorage().storedImages()).containsKey(secondKey);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_image_cleanup WHERE storage_key=?",Integer.class,secondKey.value())).isEqualTo(1);
        catalog.retryImageCleanup();
        assertThat(imageStorage().storedImages()).doesNotContainKey(secondKey);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_image_cleanup WHERE storage_key=?",Integer.class,secondKey.value())).isZero();
        assertThatThrownBy(() -> catalog.image(sku,false)).isInstanceOf(CatalogException.class);
        assertThatThrownBy(() -> catalog.uploadImage(sku,"<script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(CatalogException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_sku_image_metadata WHERE sku_id=?",Integer.class,sku)).isZero();
    }

    @Test
    void priceLinesRoundHalfUpAndTotalsSumRoundedLineAmounts() {
        var half=DecimalPriceCalculator.line(new BigDecimal("0.05"),new BigDecimal("10.00"),1);
        assertThat(half.vat()).isEqualByComparingTo("0.01");
        var total=DecimalPriceCalculator.total(java.util.List.of(
                new DecimalPriceCalculator.LineInput(new BigDecimal("0.05"),new BigDecimal("10.00"),1),
                new DecimalPriceCalculator.LineInput(new BigDecimal("0.05"),new BigDecimal("10.00"),1)));
        assertThat(total.net()).isEqualByComparingTo("0.10"); assertThat(total.vat()).isEqualByComparingTo("0.02");
        assertThat(total.gross()).isEqualByComparingTo("0.12");
    }

    private void insertProfile(UUID id,String nip) {
        jdbc.update("INSERT INTO customer_profile(user_id,company_name,nip,billing_street,billing_building_number,billing_postal_code,billing_city,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id,"Company "+nip,nip,"Ulica","1","00-001","Warszawa",at(NOW),at(NOW));
    }
    private static void authenticate(UUID customer) {
        var principal=new AccountPrincipal(customer,"customer@example.test","hash","CUSTOMER","ACTIVE",0);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
    }
    private static byte[] png(int width,int height) throws Exception {
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        var bytes=new ByteArrayOutputStream(); ImageIO.write(image,"png",bytes); return bytes.toByteArray();
    }
    private static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
