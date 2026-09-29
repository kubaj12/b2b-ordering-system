package io.github.kubaj12.online_store.catalogpricing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.kubaj12.online_store.shared.auditing.*;

@Service
public class PriceListAdministrationService {
    private final PriceListStore store; private final Clock clock; private final AuditEventRecorder audit;
    public PriceListAdministrationService(PriceListStore store,Clock clock,AuditEventRecorder audit) { this.store=store;this.clock=clock;this.audit=audit; }
    @Transactional(readOnly=true) public List<PriceListStore.PriceList> lists() { return store.lists(); }
    @Transactional(readOnly=true) public List<PriceListStore.Sku> skus() { return store.skus(); }
    @Transactional(readOnly=true) public List<PriceListStore.Customer> customers() { return store.customers(); }
    @Transactional(readOnly=true) public PriceListStore.CustomerPricePage customerPrices(UUID customerId,int page) { return store.customerPrices(requiredId(customerId),Math.max(0,page),50); }
    @Transactional(readOnly=true) public PriceListStore.PriceList list(UUID id) { var x=store.list(id);if(x==null)throw new CatalogException("price list not found");return x; }
    @Transactional public UUID create(String name) { UUID id=UUID.randomUUID();store.createList(id,required(name,160),now());return id; }
    @Transactional public void rename(UUID id,String name) { if(store.renameList(id,required(name,160),now())==null)throw new CatalogException("price list not found"); }
    @Transactional public void price(UUID list,UUID sku,BigDecimal value,UUID actor) {
        if(!store.priceListExists(list))throw new CatalogException("price list not found"); BigDecimal next=money(value);store.lockSku(sku);BigDecimal old=store.listPrice(list,sku);
        store.saveListPrice(list,sku,next,now()); if(old==null||old.compareTo(next)!=0)audit.record(PriceListAudit.LIST_PRICE.event(sku,new AuditActor(requiredId(actor)),PriceListAudit.PRICE.change(old,next),PriceListAudit.PRICE_LIST_ID.change(null,list)));
    }
    @Transactional public void priceByCode(UUID list,String code,BigDecimal value,UUID actor) { UUID sku=sku(code);price(list,sku,value,actor); }
    @Transactional public void removePrice(UUID list,UUID sku,UUID actor) { store.lockSku(sku);BigDecimal old=store.listPrice(list,sku);if(old!=null){store.removeListPrice(list,sku);audit.record(PriceListAudit.LIST_PRICE.event(sku,new AuditActor(requiredId(actor)),PriceListAudit.PRICE.change(old,null),PriceListAudit.PRICE_LIST_ID.change(null,list)));} }
    @Transactional public void assign(UUID customer,UUID list,UUID actor) { UUID id=requiredId(customer);store.lockCustomer(id);if(!store.priceListExists(list))throw new CatalogException("price list not found");UUID previous=store.assignedList(id);store.assignList(id,list,now());if(!list.equals(previous))audit.record(PriceListAudit.CUSTOMER_LIST_ASSIGNMENT.event(id,new AuditActor(requiredId(actor)),PriceListAudit.PRICE_LIST_ID.change(previous,list))); }
    @Transactional public void unassign(UUID customer,UUID actor) { UUID id=requiredId(customer);store.lockCustomer(id);UUID previous=store.assignedList(id);store.unassignList(id);if(previous!=null)audit.record(PriceListAudit.CUSTOMER_LIST_ASSIGNMENT.event(id,new AuditActor(requiredId(actor)),PriceListAudit.PRICE_LIST_ID.change(previous,null))); }
    @Transactional public void customerPrice(UUID customer,UUID sku,BigDecimal value,UUID actor) {
        UUID id=requiredId(customer);store.lockCustomer(id);BigDecimal next=money(value);store.lockSku(sku);BigDecimal old=store.customerPrice(id,sku);
        store.saveCustomerPrice(id,sku,next,now());if(old==null||old.compareTo(next)!=0)audit.record(PriceListAudit.CUSTOMER_PRICE.event(sku,new AuditActor(requiredId(actor)),PriceListAudit.PRICE.change(old,next),PriceListAudit.CUSTOMER_ID.change(null,id)));
    }
    @Transactional public void customerPriceByCode(UUID customer,String code,BigDecimal value,UUID actor) { UUID sku=sku(code);customerPrice(customer,sku,value,actor); }
    @Transactional public void removeCustomerPrice(UUID customer,UUID sku,UUID actor) { UUID id=requiredId(customer);store.lockCustomer(id);store.lockSku(sku);BigDecimal old=store.customerPrice(id,sku);if(old!=null){store.removeCustomerPrice(id,sku);audit.record(PriceListAudit.CUSTOMER_PRICE.event(sku,new AuditActor(requiredId(actor)),PriceListAudit.PRICE.change(old,null),PriceListAudit.CUSTOMER_ID.change(null,id)));} }
    private BigDecimal money(BigDecimal x){if(x==null||x.signum()<0)throw new CatalogException("price must be non-negative");try{var n=x.setScale(2,RoundingMode.UNNECESSARY);if(n.precision()>12)throw new ArithmeticException();return n;}catch(ArithmeticException e){throw new CatalogException("price must fit NUMERIC(12,2)",e);}}
    private String required(String x,int max){if(x==null||x.isBlank()||x.trim().length()>max)throw new CatalogException("invalid price list name");return x.trim();}
    private UUID sku(String code){String value=required(code,80);UUID id=store.skuIdByCode(value);if(id==null)throw new CatalogException("SKU not found");return id;}
    private UUID requiredId(UUID id){if(id==null)throw new CatalogException("identifier required");return id;}
    private java.time.Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
}
