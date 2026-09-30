package io.github.kubaj12.online_store.catalogpricing;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.kubaj12.online_store.catalogpricing.application.CustomerCatalogService;
import io.github.kubaj12.online_store.catalogpricing.application.CustomerCatalogStore;
import io.github.kubaj12.online_store.catalogpricing.application.EffectivePriceResolver;
import io.github.kubaj12.online_store.shared.money.DecimalPriceCalculator;
import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;
import io.github.kubaj12.online_store.testsupport.SecurityTestUsers;

@BrowserMvcTest(controllers = io.github.kubaj12.online_store.catalogpricing.web.CustomerCatalogController.class)
class CustomerCatalogRenderingTests {
    @Autowired MockMvc mvc;
    @MockitoBean CustomerCatalogService catalog;

    @Test
    void rendersImageStockQuantityAndUnavailableActionsFromCurrentVariantState() throws Exception {
        var available=new CustomerCatalogStore.Variant(UUID.randomUUID(),"SKU-AVAILABLE",2,true,List.of());
        var unavailable=new CustomerCatalogStore.Variant(UUID.randomUUID(),"SKU-EMPTY",0,false,List.of());
        var product=new CustomerCatalogStore.Product(UUID.randomUUID(),"Produkt demonstracyjny","Opis","Kategoria",List.of(available,unavailable));
        var price=new EffectivePriceResolver.EffectivePrice(new BigDecimal("10.00"),new BigDecimal("23.00"),EffectivePriceResolver.Source.BASE);
        var availableAmounts=DecimalPriceCalculator.line(price.unitNetPrice(),price.vatRate(),1);
        var noVatPrice=new EffectivePriceResolver.EffectivePrice(new BigDecimal("5.00"),BigDecimal.ZERO,EffectivePriceResolver.Source.BASE);
        var unavailableAmounts=DecimalPriceCalculator.line(noVatPrice.unitNetPrice(),noVatPrice.vatRate(),1);
        when(catalog.page(anyString(),anyString(),anyInt())).thenReturn(new CustomerCatalogService.Page(List.of(
                new CustomerCatalogService.Product(product,List.of(
                        new CustomerCatalogService.PricedVariant(available,price,availableAmounts),
                        new CustomerCatalogService.PricedVariant(unavailable,noVatPrice,unavailableAmounts)))),0,12,1,1));
        when(catalog.categories()).thenReturn(List.of("Kategoria"));

        mvc.perform(get("/catalog").with(SecurityTestUsers.customer()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/catalog/images/"+available.id()+"?thumbnail=true")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Dostępny · 2 szt.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Chwilowo niedostępny · 0 szt.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Brak w magazynie")));
    }
}
