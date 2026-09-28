package io.github.kubaj12.online_store.customers.web;

import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import io.github.kubaj12.online_store.customers.application.CustomerAdministrationService;
import io.github.kubaj12.online_store.customers.application.CustomerAdministrationStore;
import io.github.kubaj12.online_store.customers.application.CustomerProfileData;
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

    @Test
    void customerInvitationSeparatelyRejectsForeignCountryAndInvalidNip() throws Exception {
        mvc.perform(post("/staff/customers").with(SecurityTestUsers.employee()).with(csrf())
                .param("email", "buyer@example.test").param("profile.companyName", "Firma")
                .param("profile.nip", "5260250995").param("profile.street", "Prosta")
                .param("profile.buildingNumber", "1").param("profile.postalCode", "00-001")
                .param("profile.city", "Warszawa").param("profile.country", "DE"))
                .andExpect(status().isOk()).andExpect(view().name("customers/create"))
                .andExpect(model().attributeHasErrors("form"));

        mvc.perform(post("/staff/customers").with(SecurityTestUsers.employee()).with(csrf())
                .param("email", "buyer@example.test").param("profile.companyName", "Firma")
                .param("profile.nip", "5260250994").param("profile.street", "Prosta")
                .param("profile.buildingNumber", "1").param("profile.postalCode", "00-001")
                .param("profile.city", "Warszawa").param("profile.country", "PL"))
                .andExpect(status().isOk()).andExpect(view().name("customers/create"))
                .andExpect(model().attributeHasFieldErrors("form", "profile.nip"));
        verifyNoInteractions(customers);
    }

    @Test
    void profileEditSeparatelyRejectsForeignCountryAndInvalidNip() throws Exception {
        UUID id = UUID.randomUUID();
        var profile = CustomerProfileData.of("Firma", "5260250995", "Prosta", "1", null,
                "00-001", "Warszawa", "PL", null);
        when(customers.detail(id)).thenReturn(new CustomerAdministrationStore.Detail(
                id, "buyer@example.test", "ACTIVE", profile, Instant.EPOCH, Instant.EPOCH));

        mvc.perform(post("/staff/customers/" + id).with(SecurityTestUsers.employee()).with(csrf())
                .param("companyName", "Firma").param("nip", "5260250995")
                .param("street", "Prosta").param("buildingNumber", "1")
                .param("postalCode", "00-001").param("city", "Warszawa").param("country", "DE"))
                .andExpect(status().isOk()).andExpect(view().name("customers/edit"))
                .andExpect(model().attributeHasErrors("form"));

        mvc.perform(post("/staff/customers/" + id).with(SecurityTestUsers.employee()).with(csrf())
                .param("companyName", "Firma").param("nip", "5260250994")
                .param("street", "Prosta").param("buildingNumber", "1")
                .param("postalCode", "00-001").param("city", "Warszawa").param("country", "PL"))
                .andExpect(status().isOk()).andExpect(view().name("customers/edit"))
                .andExpect(model().attributeHasFieldErrors("form", "nip"));
        verify(customers, times(2)).detail(id);
        verify(customers, never()).update(any(), any());
        verifyNoMoreInteractions(customers);
    }
}
