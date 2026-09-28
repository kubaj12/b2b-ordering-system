package io.github.kubaj12.online_store.customers.web;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import io.github.kubaj12.online_store.customers.application.CustomerAdministrationService;
import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;
import io.github.kubaj12.online_store.testsupport.InMemoryAuthenticationAccountStore;
import io.github.kubaj12.online_store.testsupport.SecurityTestUsers;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@BrowserMvcTest(controllers = CustomerAdministrationController.class)
class CustomerAdministrationAuthorizationTests {
    @Autowired MockMvc mvc;
    @MockitoBean CustomerAdministrationService customers;

    @Test
    void customerCannotReadProfilesOrAdministrationFormsByGuessingOwnOrOtherIds() throws Exception {
        UUID ownId = InMemoryAuthenticationAccountStore.CUSTOMER_ID;
        UUID otherId = UUID.randomUUID();
        for (String path : new String[] {"/staff/customers", "/staff/customers/new",
                "/staff/customers/" + ownId, "/staff/customers/" + ownId + "/edit",
                "/staff/customers/" + otherId, "/staff/customers/" + otherId + "/edit"}) {
            mvc.perform(get(path).with(SecurityTestUsers.customer())).andExpect(status().isForbidden());
        }
        mvc.perform(get("/staff/customers/" + ownId + "/edit").with(SecurityTestUsers.customer())
                .header("HX-Request", "true"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(customers);
    }

    @Test
    void customerCannotSubmitCraftedAdministrationMutations() throws Exception {
        UUID target = UUID.randomUUID();
        mvc.perform(post("/staff/customers").with(SecurityTestUsers.customer()).with(csrf())
                .param("email", "attacker@example.test"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/staff/customers/" + target).with(SecurityTestUsers.customer()).with(csrf())
                .param("companyName", "Tampered"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/staff/customers/" + target + "/block").with(SecurityTestUsers.customer()).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/staff/customers/" + target + "/unblock").with(SecurityTestUsers.customer()).with(csrf())
                .header("HX-Request", "true"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(customers);
    }

    @Test
    void staffCanStillOpenCustomerDirectoryAndAdministrationForm() throws Exception {
        when(customers.directory()).thenReturn(new CustomerAdministrationService.Directory(java.util.List.of(), java.util.List.of()));
        mvc.perform(get("/staff/customers").with(SecurityTestUsers.employee()))
                .andExpect(status().isOk());
        mvc.perform(get("/staff/customers/new").with(SecurityTestUsers.administrator()))
                .andExpect(status().isOk());
        verify(customers).directory();
        verifyNoMoreInteractions(customers);
    }
}
