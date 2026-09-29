package io.github.kubaj12.online_store.catalogpricing.application;

import io.github.kubaj12.online_store.shared.money.DecimalPriceCalculator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerCatalogService {
    public record PricedVariant(CustomerCatalogStore.Variant variant, EffectivePriceResolver.EffectivePrice price,
            DecimalPriceCalculator.Amounts amounts) { }
    public record Product(CustomerCatalogStore.Product product, List<PricedVariant> variants) { }
    public record Page(List<Product> products, int number, int size, long totalElements, int totalPages) { }
    private final CustomerCatalogStore store;
    private final EffectivePriceResolver prices;
    public CustomerCatalogService(CustomerCatalogStore store, EffectivePriceResolver prices) { this.store=store; this.prices=prices; }
    @Transactional(readOnly=true) public Page page(String search, String category, int requestedPage) {
        String term=search==null?"":search.trim();
        if(term.length()>120) term=term.substring(0,120);
        String selected=category==null?"":category.trim();
        int page=Math.max(0,Math.min(requestedPage,1_000_000));
        int size=12;
        var result=store.page(term,selected,page,size);
        int totalPages=(int)Math.min(Integer.MAX_VALUE,(result.total()+size-1)/size);
        if(totalPages>0 && page>=totalPages) {
            page=totalPages-1;
            result=store.page(term,selected,page,size);
        }
        var skuIds=result.products().stream().flatMap(p->p.variants().stream()).map(CustomerCatalogStore.Variant::id).toList();
        var effectivePrices=prices.resolveAll(skuIds);
        List<Product> rows=result.products().stream().map(p->new Product(p,p.variants().stream().map(v->{
            var effective=effectivePrices.get(v.id());
            return new PricedVariant(v,effective,DecimalPriceCalculator.line(effective.unitNetPrice(),effective.vatRate(),1));
        }).toList())).toList();
        return new Page(rows,page,size,result.total(),totalPages);
    }
    @Transactional(readOnly=true) public List<String> categories() { return store.categories(); }
}
