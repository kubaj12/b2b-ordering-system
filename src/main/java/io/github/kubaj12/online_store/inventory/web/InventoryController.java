package io.github.kubaj12.online_store.inventory.web;

import io.github.kubaj12.online_store.inventory.application.InventoryService;
import io.github.kubaj12.online_store.shared.time.ApplicationTimeZone;
import io.github.kubaj12.online_store.shared.web.request.BrowserResponse;
import io.github.kubaj12.online_store.shared.web.request.HtmxRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.time.ZoneId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
@Validated
@PreAuthorize("hasAnyRole('EMPLOYEE','ADMIN')")
@RequestMapping("/staff/inventory")
public class InventoryController {
    private final InventoryService inventory;
    private final ZoneId displayZone;
    public InventoryController(InventoryService inventory, ApplicationTimeZone timeZone) {
        this.inventory=inventory; this.displayZone=timeZone.zoneId();
    }

    @GetMapping public ModelAndView list(@RequestParam(defaultValue="") @Size(max=120) String q,
            @RequestParam(defaultValue="") @Size(max=120) String category,@RequestParam(defaultValue="0") @Min(0) @Max(1_000_000) int page,
            HttpServletRequest request,HttpServletResponse response) {
        var data=inventory.page(q,category,page); var retained=new LinkedHashMap<String,String[]>();
        if(q!=null&&!q.isBlank()) retained.put("q",new String[]{q.trim()});
        if(category!=null&&!category.isBlank()) retained.put("category",new String[]{category.trim()});
        var model=Map.<String,Object>of("inventoryPage",data,"categories",inventory.categories(),"q",q,
                "displayZone",displayZone,
                "selectedCategory",category,"retainedParameters",retained);
        return BrowserResponse.render(HtmxRequest.from(request),response,"inventory/list","inventory/list :: content",model);
    }
    @PostMapping public ModelAndView update(@RequestParam UUID skuId,@RequestParam @Min(0) int quantity,
            @RequestParam @Min(0) long version,@RequestParam(defaultValue="") @Size(max=120) String q,
            @RequestParam(defaultValue="") @Size(max=120) String category,@RequestParam(defaultValue="0") @Min(0) @Max(1_000_000) int page,
            @AuthenticationPrincipal(expression="accountId") UUID actor,HttpServletRequest request,HttpServletResponse response) {
        inventory.update(skuId,quantity,version,actor);
        return BrowserResponse.redirect(HtmxRequest.from(request),response,
                "/staff/inventory?q="+encode(q)+"&category="+encode(category)+"&page="+Math.max(0,page));
    }
    private static String encode(String value) {
        return java.net.URLEncoder.encode(value==null?"":value.trim(),java.nio.charset.StandardCharsets.UTF_8);
    }
}
