package io.github.kubaj12.online_store.catalogpricing.web;

import io.github.kubaj12.online_store.catalogpricing.application.*;
import io.github.kubaj12.online_store.shared.web.error.WebErrorException;
import io.github.kubaj12.online_store.shared.web.request.*;
import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

@Controller @PreAuthorize("hasAnyRole('EMPLOYEE','ADMIN')") @RequestMapping("/staff/price-lists")
public class PriceListController {
    private final PriceListAdministrationService service;
    public PriceListController(PriceListAdministrationService service){this.service=service;}
    @GetMapping public ModelAndView index(HttpServletRequest req,HttpServletResponse res){return render(req,res,"catalog/price-lists","catalog/price-lists :: content",Map.of("lists",service.lists(),"customers",service.customers()));}
    @PostMapping public ModelAndView create(@RequestParam String name,HttpServletRequest req,HttpServletResponse res){try{service.create(name);}catch(DataIntegrityViolationException e){throw WebErrorException.conflict();}catch(CatalogException e){throw WebErrorException.validation();}return index(req,res);}
    @GetMapping("/{id}") public ModelAndView detail(@PathVariable UUID id,HttpServletRequest req,HttpServletResponse res){return render(req,res,"catalog/price-list-detail","catalog/price-list-detail :: content",Map.of("priceList",get(id)));}
    @GetMapping("/customers/{customerId}") public ModelAndView customer(@PathVariable UUID customerId,@RequestParam(defaultValue="0") int page,HttpServletRequest req,HttpServletResponse res){
        var customer=service.customers().stream().filter(c->c.id().equals(customerId)).findFirst().orElseThrow(WebErrorException::notFound);
        return render(req,res,"catalog/customer-prices","catalog/customer-prices :: content",Map.of("customer",customer,"priceLists",service.lists(),"pricePage",service.customerPrices(customerId,Math.max(0,page))));
    }
    @PostMapping("/{id}") public ModelAndView rename(@PathVariable UUID id,@RequestParam String name,HttpServletRequest req,HttpServletResponse res){try{service.rename(id,name);}catch(DataIntegrityViolationException e){throw WebErrorException.conflict();}catch(CatalogException e){throw WebErrorException.validation();}return redirect(id,req,res);}
    @PostMapping("/{id}/items") public ModelAndView put(@PathVariable UUID id,@RequestParam String skuCode,@RequestParam BigDecimal netPrice,@AuthenticationPrincipal(expression="accountId") UUID actor,HttpServletRequest req,HttpServletResponse res){try{service.priceByCode(id,skuCode,netPrice,actor);}catch(DataIntegrityViolationException e){throw WebErrorException.notFound();}catch(CatalogException e){throw WebErrorException.validation();}return redirect(id,req,res);}
    @PostMapping("/{id}/items/{skuId}/remove") public ModelAndView remove(@PathVariable UUID id,@PathVariable UUID skuId,@AuthenticationPrincipal(expression="accountId") UUID actor,HttpServletRequest req,HttpServletResponse res){service.removePrice(id,skuId,actor);return redirect(id,req,res);}
    @PostMapping("/customers/{customerId}/assignment") public ModelAndView assign(@PathVariable UUID customerId,@RequestParam(required=false) UUID priceListId,@AuthenticationPrincipal(expression="accountId") UUID actor,HttpServletRequest req,HttpServletResponse res){try{if(priceListId==null)service.unassign(customerId,actor);else service.assign(customerId,priceListId,actor);}catch(CatalogException e){throw WebErrorException.validation();}return index(req,res);}
    @PostMapping("/customers/{customerId}/prices") public ModelAndView override(@PathVariable UUID customerId,@RequestParam String skuCode,@RequestParam BigDecimal netPrice,@AuthenticationPrincipal(expression="accountId") UUID actor,HttpServletRequest req,HttpServletResponse res){try{service.customerPriceByCode(customerId,skuCode,netPrice,actor);}catch(CatalogException e){throw WebErrorException.validation();}return BrowserResponse.redirect(HtmxRequest.from(req),res,"/staff/price-lists/customers/"+customerId);}
    @PostMapping("/customers/{customerId}/prices/{skuId}/remove") public ModelAndView removeOverride(@PathVariable UUID customerId,@PathVariable UUID skuId,@AuthenticationPrincipal(expression="accountId") UUID actor,HttpServletRequest req,HttpServletResponse res){service.removeCustomerPrice(customerId,skuId,actor);return BrowserResponse.redirect(HtmxRequest.from(req),res,"/staff/price-lists/customers/"+customerId);}
    private PriceListStore.PriceList get(UUID id){try{return service.list(id);}catch(CatalogException e){throw WebErrorException.notFound();}}
    private static ModelAndView redirect(UUID id,HttpServletRequest req,HttpServletResponse res){return BrowserResponse.redirect(HtmxRequest.from(req),res,"/staff/price-lists/"+id);}
    private static ModelAndView render(HttpServletRequest req,HttpServletResponse res,String view,String fragment,Map<String,?> model){return BrowserResponse.render(HtmxRequest.from(req),res,view,fragment,model);}
}
