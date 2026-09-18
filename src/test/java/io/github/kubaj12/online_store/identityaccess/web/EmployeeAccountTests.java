package io.github.kubaj12.online_store.identityaccess.web;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import io.github.kubaj12.online_store.identityaccess.application.*;
import io.github.kubaj12.online_store.testsupport.*;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@BrowserMvcTest(controllers = EmployeeAccountController.class)
class EmployeeAccountTests {
    @Autowired MockMvc mvc;
    @MockitoBean EmployeeAccountService accounts;
    @MockitoBean InvitationService invitations;
    @Test void directoryRendersStaffAndCsrfFormsForAdminAndHtmx() throws Exception {
        var staff = new EmployeeAccountStore.Staff(UUID.randomUUID(), "staff@example.test", "EMPLOYEE", "ACTIVE", null);
        when(accounts.directory(InMemoryAuthenticationAccountStore.ADMIN_ID)).thenReturn(new EmployeeAccountService.Directory(List.of(staff), List.of()));
        mvc.perform(get("/admin/employees").with(SecurityTestUsers.administrator())).andExpect(status().isOk())
            .andExpect(content().string(containsString("staff@example.test"))).andExpect(content().string(containsString("_csrf")));
        mvc.perform(get("/admin/employees").with(SecurityTestUsers.administrator()).header("HX-Request", "true"))
            .andExpect(status().isOk()).andExpect(view().name("identityaccess/employees :: directory"));
    }
    @Test void roleAndCsrfBoundariesApplyToEveryAction() throws Exception {
        mvc.perform(get("/admin/employees")).andExpect(status().isFound());
        for (var user : List.of(SecurityTestUsers.customer(), SecurityTestUsers.employee())) {
            mvc.perform(get("/admin/employees").with(user)).andExpect(status().isForbidden());
            for (String suffix : List.of("invite", UUID.randomUUID()+"/block", UUID.randomUUID()+"/unblock"))
                mvc.perform(post("/admin/employees/"+suffix).with(user).with(csrf()).param("email", "new@example.test")).andExpect(status().isForbidden());
        }
        mvc.perform(post("/admin/employees/invite").with(SecurityTestUsers.administrator()).param("email", "new@example.test")).andExpect(status().isForbidden());
        verifyNoInteractions(accounts, invitations);
    }
    @Test void storedActorAndFixedEmployeeRoleAreUsedAndHtmxRedirects() throws Exception {
        mvc.perform(post("/admin/employees/invite").with(SecurityTestUsers.administrator()).with(csrf()).param("email", "new@example.test").param("role", "ADMIN"))
            .andExpect(status().isSeeOther());
        verify(invitations).inviteEmployee("new@example.test", InMemoryAuthenticationAccountStore.ADMIN_ID);
        UUID target = UUID.randomUUID();
        mvc.perform(post("/admin/employees/"+target+"/block").with(SecurityTestUsers.administrator()).with(csrf()).header("HX-Request", "true"))
            .andExpect(status().isNoContent()).andExpect(header().string("HX-Redirect", "/admin/employees"));
        verify(accounts).block(InMemoryAuthenticationAccountStore.ADMIN_ID, target);
    }
}
