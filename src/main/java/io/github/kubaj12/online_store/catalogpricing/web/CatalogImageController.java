package io.github.kubaj12.online_store.catalogpricing.web;

import io.github.kubaj12.online_store.catalogpricing.application.CatalogAdministrationService;
import io.github.kubaj12.online_store.catalogpricing.application.CatalogException;
import io.github.kubaj12.online_store.shared.web.error.WebErrorException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class CatalogImageController {
    private final CatalogAdministrationService catalog;
    public CatalogImageController(CatalogAdministrationService catalog) { this.catalog=catalog; }

    @GetMapping("/catalog/images/{skuId}")
    @ResponseBody
    public ResponseEntity<byte[]> image(@PathVariable UUID skuId, @RequestParam(defaultValue="false") boolean thumbnail) {
        try {
            var image=catalog.image(skuId,thumbnail);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                    .header("X-Content-Type-Options","nosniff")
                    .header(HttpHeaders.CONTENT_DISPOSITION,"inline")
                    .cacheControl(CacheControl.noCache()).body(image.bytes());
        } catch(CatalogException ex) { throw WebErrorException.notFound(); }
    }

    @PostMapping("/staff/catalog/sku/{skuId}/image")
    @PreAuthorize("hasAnyRole('EMPLOYEE','ADMIN')")
    public String upload(@PathVariable UUID skuId, @RequestParam UUID productId, @RequestParam("image") MultipartFile file) {
        try { catalog.uploadImage(skuId,file.getBytes()); }
        catch(CatalogException ex) { throw WebErrorException.validation(); }
        catch(java.io.IOException ex) { throw WebErrorException.validation(); }
        return "redirect:/staff/catalog/"+productId;
    }

    @PostMapping("/staff/catalog/sku/{skuId}/image/remove")
    @PreAuthorize("hasAnyRole('EMPLOYEE','ADMIN')")
    public String remove(@PathVariable UUID skuId, @RequestParam UUID productId) { catalog.removeImage(skuId); return "redirect:/staff/catalog/"+productId; }
}
