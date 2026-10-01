package io.github.kubaj12.online_store.inventory.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.github.kubaj12.online_store.shared.auditing.AuditActor;
import io.github.kubaj12.online_store.shared.auditing.AuditEventRecorder;
import io.github.kubaj12.online_store.shared.web.error.WebErrorException;

@Service
public class InventoryService {
    public record Page(List<InventoryStore.Item> items, int number, int size, long totalElements, int totalPages) { }
    private final InventoryStore store;
    private final AuditEventRecorder audit;
    public InventoryService(InventoryStore store, AuditEventRecorder audit) { this.store=store; this.audit=audit; }

    @Transactional(readOnly=true) public Page page(String search, String category, int requestedPage) {
        String term=search==null?"":search.trim(); if(term.length()>120) term=term.substring(0,120);
        String selected=category==null?"":category.trim();
        int page=Math.max(0,Math.min(requestedPage,1_000_000)), size=20;
        var result=store.page(term,selected,page,size);
        int pages=(int)Math.min(Integer.MAX_VALUE,(result.total()+size-1)/size);
        if(pages>0 && page>=pages) { page=pages-1; result=store.page(term,selected,page,size); }
        return new Page(result.items(),page,size,result.total(),pages);
    }
    @Transactional(readOnly=true) public List<String> categories() { return store.categories(); }

    @Transactional public void update(UUID skuId, int quantity, long version, UUID actorId) {
        if(quantity<0 || version<0) throw WebErrorException.validation("inventory.validation.quantity");
        var result=store.update(skuId,quantity,version,actorId);
        if(result==null) throw WebErrorException.notFound();
        if(!result.updated()) throw WebErrorException.conflict("inventory.conflict",result.previousQuantity());
        if(result.previousQuantity()!=quantity) audit.record(InventoryAudit.QUANTITY_CHANGED.event(skuId,
                new AuditActor(actorId),InventoryAudit.QUANTITY.change(result.previousQuantity(),quantity)));
    }
}
