package io.github.kubaj12.online_store.catalogpricing;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import io.github.kubaj12.online_store.catalogpricing.application.CatalogAdministrationService;
import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;
import io.github.kubaj12.online_store.testsupport.SecurityTestUsers;

@BrowserMvcTest(controllers = io.github.kubaj12.online_store.catalogpricing.web.CatalogImageController.class)
class CatalogImageAuthorizationTests {
    @Autowired MockMvc mvc;
    @MockitoBean CatalogAdministrationService catalog;
    private final UUID sku = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();

    @Test
    void imageReadsRequireAuthenticationAndServeInlineWithNosniff() throws Exception {
        when(catalog.image(sku, false)).thenReturn(new CatalogAdministrationService.ImageView("image/png", new byte[]{1,2,3}));
        mvc.perform(get("/catalog/images/{id}",sku)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/catalog/images/{id}",sku).with(SecurityTestUsers.customer()))
                .andExpect(status().isOk()).andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(header().string("Content-Disposition","inline"));
    }

    @Test
    void onlyStaffCanUploadOrRemoveImages() throws Exception {
        var file = new MockMultipartFile("image","photo.png","image/png",new byte[]{1,2,3});
        mvc.perform(multipart("/staff/catalog/sku/{id}/image",sku).file(file)
                .param("productId",product.toString()).with(csrf()).with(SecurityTestUsers.customer()))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/staff/catalog/sku/{id}/image/remove",sku)
                .param("productId",product.toString()).with(csrf()).with(SecurityTestUsers.customer()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/staff/catalog/sku/{id}/image",sku).file(file)
                .param("productId",product.toString()).with(csrf()).with(SecurityTestUsers.employee()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/staff/catalog/sku/{id}/image/remove",sku)
                .param("productId",product.toString()).with(csrf()).with(SecurityTestUsers.employee()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(multipart("/staff/catalog/sku/{id}/image",sku).file(file)
                .param("productId",product.toString()).with(csrf()).with(SecurityTestUsers.administrator()))
                .andExpect(status().is3xxRedirection());
    }

}
