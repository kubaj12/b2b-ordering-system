package io.github.kubaj12.online_store.catalogpricing.web;

import io.github.kubaj12.online_store.catalogpricing.application.CustomerCatalogService;
import io.github.kubaj12.online_store.shared.web.request.BrowserResponse;
import io.github.kubaj12.online_store.shared.web.request.HtmxRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
@PreAuthorize("hasRole('CUSTOMER')")
@RequestMapping("/catalog")
public class CustomerCatalogController {
    private final CustomerCatalogService catalog;
    public CustomerCatalogController(CustomerCatalogService catalog) { this.catalog=catalog; }
    @GetMapping public ModelAndView list(@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="") String category, @RequestParam(defaultValue="0") int page,
            HttpServletRequest request, HttpServletResponse response) {
        var data=catalog.page(q,category,page);
        var retained=new LinkedHashMap<String,String[]>();
        if(q!=null&&!q.isBlank()) retained.put("q",new String[]{q.trim()});
        if(category!=null&&!category.isBlank()) retained.put("category",new String[]{category.trim()});
        var model=Map.<String,Object>of("catalogPage",data,"categories",catalog.categories(),"q",q,
                "selectedCategory",category,"retainedParameters",retained);
        return BrowserResponse.render(HtmxRequest.from(request),response,"catalog/customer-list","catalog/customer-list :: content",model);
    }
}
