package io.github.kubaj12.online_store.catalogpricing.web;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import io.github.kubaj12.online_store.catalogpricing.application.*;
import io.github.kubaj12.online_store.shared.web.error.WebErrorException;
import io.github.kubaj12.online_store.shared.web.request.*;

@Controller
@PreAuthorize("hasAnyRole('EMPLOYEE','ADMIN')")
@RequestMapping("/staff/catalog")
public class CatalogAdministrationController {
    private final CatalogAdministrationService catalog;
    public CatalogAdministrationController(CatalogAdministrationService catalog) { this.catalog=catalog; }
    @GetMapping public ModelAndView list(HttpServletRequest req,HttpServletResponse res) {
        return render(req,res,"catalog/staff-list","catalog/staff-list :: content",Map.of("products",catalog.products()));
    }
    @GetMapping("/new") public ModelAndView newProduct(HttpServletRequest req,HttpServletResponse res) {
        return render(req,res,"catalog/product-form","catalog/product-form :: content",Map.of("form",new ProductForm(),"editing",false));
    }
    @PostMapping public ModelAndView create(@Valid @ModelAttribute("form") ProductForm form, BindingResult errors,HttpServletRequest req,HttpServletResponse res) {
        if(errors.hasErrors()) {
            var model=new java.util.LinkedHashMap<String,Object>(); model.put("form",form); model.put("editing",false);
            model.put(org.springframework.validation.BindingResult.MODEL_KEY_PREFIX+"form",errors);
            return render(req,res,"catalog/product-form","catalog/product-form :: content",model);
        }
        try { UUID id=catalog.createProduct(form.name(),form.description(),form.category()); return BrowserResponse.redirect(HtmxRequest.from(req),res,"/staff/catalog/"+id); }
        catch(CatalogException ex) { throw WebErrorException.validation(); }
    }
    @GetMapping("/{id}") public ModelAndView detail(@PathVariable UUID id,HttpServletRequest req,HttpServletResponse res) {
        var product=product(id);
        return render(req,res,"catalog/product-detail","catalog/product-detail :: content",detailModel(product,new ProductForm(product.name(),product.description(),product.category())));
    }
    @PostMapping("/{id}") public ModelAndView update(@PathVariable UUID id,@Valid @ModelAttribute("productForm") ProductForm form,BindingResult errors,HttpServletRequest req,HttpServletResponse res) {
        if(errors.hasErrors()) return detailWithForm(id,form,errors,req,res);
        try { catalog.updateProduct(id,form.name(),form.description(),form.category()); }
        catch(CatalogException ex) { throw WebErrorException.validation(); }
        return redirect(id,req,res);
    }
    @PostMapping("/{id}/skus") public ModelAndView createSku(@PathVariable UUID id,@Valid @ModelAttribute("skuForm") SkuForm form,BindingResult errors,@AuthenticationPrincipal(expression="accountId") UUID actor,HttpServletRequest req,HttpServletResponse res) {
        if(errors.hasErrors()) return detailWithSkuForm(id,form,errors,req,res);
        try { catalog.createSku(id,form.code(),form.baseNetPrice(),form.vatRate(),actor); }
        catch(DataIntegrityViolationException ex) { throw WebErrorException.conflict(); }
        catch(CatalogException ex) { throw WebErrorException.validation(); }
        return redirect(id,req,res);
    }
    @PostMapping("/sku/{skuId}/code") public ModelAndView updateSkuCode(@PathVariable UUID skuId,@RequestParam UUID productId,@RequestParam @jakarta.validation.constraints.NotBlank String code,HttpServletRequest req,HttpServletResponse res) {
        try { catalog.updateSku(skuId,code); } catch(DataIntegrityViolationException ex) { throw WebErrorException.conflict(); } catch(CatalogException ex) { throw WebErrorException.validation(); }
        return redirect(productId,req,res);
    }
    @PostMapping("/sku/{skuId}/pricing") public ModelAndView pricing(@PathVariable UUID skuId,@RequestParam UUID productId,@RequestParam BigDecimal baseNetPrice,@RequestParam BigDecimal vatRate,@AuthenticationPrincipal(expression="accountId") UUID actor,HttpServletRequest req,HttpServletResponse res) {
        try { catalog.changeBasePriceAndVat(skuId,baseNetPrice,vatRate,actor); } catch(CatalogException ex) { throw WebErrorException.validation(); }
        return redirect(productId,req,res);
    }
    @PostMapping("/{id}/activate") public ModelAndView activate(@PathVariable UUID id,HttpServletRequest req,HttpServletResponse res) { try { catalog.activateProduct(id); } catch(CatalogException ex) { throw WebErrorException.validation(); } return redirect(id,req,res); }
    @PostMapping("/{id}/deactivate") public ModelAndView deactivate(@PathVariable UUID id,HttpServletRequest req,HttpServletResponse res) { catalog.deactivateProduct(id); return redirect(id,req,res); }
    @PostMapping("/sku/{skuId}/activate") public ModelAndView activateSku(@PathVariable UUID skuId,@RequestParam UUID productId,HttpServletRequest req,HttpServletResponse res) { try { catalog.activateSku(skuId); } catch(CatalogException ex) { throw WebErrorException.validation(); } return redirect(productId,req,res); }
    @PostMapping("/sku/{skuId}/deactivate") public ModelAndView deactivateSku(@PathVariable UUID skuId,@RequestParam UUID productId,HttpServletRequest req,HttpServletResponse res) { catalog.deactivateSku(skuId); return redirect(productId,req,res); }
    @PostMapping("/sku/{skuId}/variant") public ModelAndView assignVariant(@PathVariable UUID skuId,@RequestParam UUID productId,@RequestParam UUID definitionId,@RequestParam UUID valueId,HttpServletRequest req,HttpServletResponse res) { try { catalog.assignVariantAttribute(skuId,definitionId,valueId); } catch(DataIntegrityViolationException ex) { throw WebErrorException.validation(); } return redirect(productId,req,res); }
    @PostMapping("/sku/{skuId}/variant/remove") public ModelAndView removeVariant(@PathVariable UUID skuId,@RequestParam UUID productId,@RequestParam UUID definitionId,HttpServletRequest req,HttpServletResponse res) { catalog.removeVariantAttribute(skuId,definitionId); return redirect(productId,req,res); }
    @PostMapping("/attributes") public ModelAndView createDefinition(@RequestParam String name,HttpServletRequest req,HttpServletResponse res) {
        try { catalog.createAttributeDefinition(name); }
        catch(DataIntegrityViolationException ex) { throw WebErrorException.conflict(); }
        catch(CatalogException ex) { throw WebErrorException.validation(); }
        return BrowserResponse.redirect(HtmxRequest.from(req),res,"/staff/catalog");
    }
    @PostMapping("/attributes/{definitionId}/values") public ModelAndView createValue(@PathVariable UUID definitionId,@RequestParam String value,HttpServletRequest req,HttpServletResponse res) {
        try { catalog.createAttributeValue(definitionId,value); }
        catch(DataIntegrityViolationException ex) { throw WebErrorException.conflict(); }
        catch(CatalogException ex) { throw WebErrorException.validation(); }
        return BrowserResponse.redirect(HtmxRequest.from(req),res,"/staff/catalog");
    }
    private CatalogStore.ProductDetail product(UUID id) { try { return catalog.product(id); } catch(CatalogException ex) { throw WebErrorException.notFound(); } }
    private ModelAndView detailWithForm(UUID id,ProductForm form,BindingResult errors,HttpServletRequest req,HttpServletResponse res) {
        var model=detailModel(product(id),form,new SkuForm()); model.put(org.springframework.validation.BindingResult.MODEL_KEY_PREFIX+"productForm",errors);
        return render(req,res,"catalog/product-detail","catalog/product-detail :: content",model);
    }
    private ModelAndView detailWithSkuForm(UUID id,SkuForm skuForm,BindingResult errors,HttpServletRequest req,HttpServletResponse res) {
        var p=product(id); var model=detailModel(p,new ProductForm(p.name(),p.description(),p.category()),skuForm);
        model.put(org.springframework.validation.BindingResult.MODEL_KEY_PREFIX+"skuForm",errors);
        return render(req,res,"catalog/product-detail","catalog/product-detail :: content",model);
    }
    private static Map<String,Object> detailModel(CatalogStore.ProductDetail product, ProductForm form) {
        return detailModel(product,form,new SkuForm());
    }
    private static Map<String,Object> detailModel(CatalogStore.ProductDetail product, ProductForm form,SkuForm skuForm) {
        var amounts=new java.util.HashMap<UUID,CatalogAdministrationService.ReferenceAmounts>();
        for(var sku:product.skus()) amounts.put(sku.id(),CatalogAdministrationService.referenceAmounts(sku.baseNetPrice(),sku.vatRate()));
        var model=new java.util.LinkedHashMap<String,Object>(); model.put("product",product); model.put("productForm",form);
        model.put("skuForm",skuForm); model.put("attributeForm",new NamedValueForm()); model.put("skuAmounts",amounts); return model;
    }
    private static ModelAndView redirect(UUID id,HttpServletRequest req,HttpServletResponse res) { return BrowserResponse.redirect(HtmxRequest.from(req),res,"/staff/catalog/"+id); }
    private static ModelAndView render(HttpServletRequest req,HttpServletResponse res,String view,String fragment,Map<String,?> model) { return BrowserResponse.render(HtmxRequest.from(req),res,view,fragment,model); }
}
