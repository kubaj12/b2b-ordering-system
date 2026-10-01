package io.github.kubaj12.online_store.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.kubaj12.online_store.inventory.application.InventoryService;
import io.github.kubaj12.online_store.inventory.application.InventoryStore;
import io.github.kubaj12.online_store.shared.time.ApplicationTimeZone;
import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;
import io.github.kubaj12.online_store.testsupport.InMemoryAuthenticationAccountStore;
import io.github.kubaj12.online_store.testsupport.SecurityTestUsers;
import io.github.kubaj12.online_store.shared.web.error.WebErrorException;
import io.github.kubaj12.online_store.shared.web.request.HtmxHeaders;

@BrowserMvcTest(controllers = io.github.kubaj12.online_store.inventory.web.InventoryController.class)
@Import(InventoryControllerTests.TimeZoneConfiguration.class)
class InventoryControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean InventoryService inventory;
    private final UUID sku=UUID.randomUUID();

    @Test void staffCanRenderCurrentAndLastChangeAndFilterPage() throws Exception {
        var item=new InventoryStore.Item(sku,"Wkręty","FIX-1","Mocowania",5,3,"employee@example.test",Instant.parse("2026-10-01T08:30:00Z"));
        when(inventory.page("FIX","Mocowania",2)).thenReturn(new InventoryService.Page(List.of(item),2,20,45,3));
        when(inventory.categories()).thenReturn(List.of("Mocowania"));
        var response=mvc.perform(get("/staff/inventory").param("q","FIX").param("category","Mocowania").param("page","2").with(SecurityTestUsers.employee()))
                .andExpect(status().isOk()).andReturn();
        String html=new String(response.getResponse().getContentAsByteArray(),StandardCharsets.UTF_8);
        assertThat(html).contains("FIX-1").contains("employee@example.test").contains("01.10.2026 10:30")
                .contains("name=\"version\" value=\"3\"").contains("name=\"page\" value=\"2\"");
        verify(inventory).page("FIX","Mocowania",2);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeZoneConfiguration {
        @Bean ApplicationTimeZone applicationTimeZone() {
            return new ApplicationTimeZone(java.time.ZoneId.of("Europe/Warsaw"));
        }
    }

    @Test void customersAreDeniedAndCsrfProtectsStaffWrites() throws Exception {
        mvc.perform(get("/staff/inventory").with(SecurityTestUsers.customer())).andExpect(status().isForbidden());
        mvc.perform(post("/staff/inventory").param("skuId",sku.toString()).param("quantity","4").param("version","0")
                .with(SecurityTestUsers.employee())).andExpect(status().isForbidden());
        verifyNoInteractions(inventory);
    }

    @Test void staffWritesValidateIntegerAndBoundaryParametersBeforeCallingService() throws Exception {
        mvc.perform(post("/staff/inventory").param("skuId",sku.toString()).param("quantity","-1").param("version","0")
                .with(csrf()).with(SecurityTestUsers.employee())).andExpect(status().isBadRequest());
        mvc.perform(post("/staff/inventory").param("skuId",sku.toString()).param("quantity","2147483648").param("version","0")
                .with(csrf()).with(SecurityTestUsers.employee())).andExpect(status().isBadRequest());
        mvc.perform(get("/staff/inventory").param("page","1000001").with(SecurityTestUsers.employee()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(inventory);
    }

    @Test void validWriteUsesCurrentActorAndCsrfToken() throws Exception {
        mvc.perform(post("/staff/inventory").param("skuId",sku.toString()).param("quantity","0").param("version","8")
                .with(csrf()).with(SecurityTestUsers.employee())).andExpect(status().is3xxRedirection());
        verify(inventory).update(sku,0,8,InMemoryAuthenticationAccountStore.EMPLOYEE_ID);
    }

    @Test void staleWriteRendersLatestQuantityForFullPageAndHtmx() throws Exception {
        doThrow(WebErrorException.conflict("inventory.conflict",12))
                .when(inventory).update(sku,4,2,InMemoryAuthenticationAccountStore.EMPLOYEE_ID);
        var full=mvc.perform(post("/staff/inventory").param("skuId",sku.toString()).param("quantity","4").param("version","2")
                        .with(csrf()).with(SecurityTestUsers.employee()))
                .andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.containsString("Aktualna ilość: 12")))
                .andReturn();
        assertThat(full.getResponse().getHeader(HtmxHeaders.HANDLED_ERROR)).isNull();

        mvc.perform(post("/staff/inventory").param("skuId",sku.toString()).param("quantity","4").param("version","2")
                        .header(HtmxHeaders.REQUEST,"true").with(csrf()).with(SecurityTestUsers.employee()))
                .andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.containsString("Aktualna ilość: 12")))
                .andExpect(header().string(HtmxHeaders.HANDLED_ERROR,"true"))
                .andExpect(header().string(HtmxHeaders.RETARGET,"#main-content"))
                .andExpect(header().string(HtmxHeaders.RESWAP,"innerHTML"));
    }
}
