package io.github.kubaj12.online_store.identityaccess.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;

import io.github.kubaj12.online_store.identityaccess.application.PasswordChangeService;
import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;
import io.github.kubaj12.online_store.testsupport.InMemoryAuthenticationAccountStore;
import io.github.kubaj12.online_store.testsupport.SecurityTestUsers;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@BrowserMvcTest(controllers = PasswordChangeController.class)
class PasswordChangeControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean PasswordChangeService service;

    @Test void requiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/account/password")).andExpect(status().isFound());
        mvc.perform(post("/account/password").with(SecurityTestUsers.customer())
                .param("currentPassword", "old").param("password", "NewPassword123")
                .param("passwordConfirmation", "NewPassword123")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void rejectsMismatchWithoutChangingPassword() throws Exception {
        mvc.perform(post("/account/password").with(SecurityTestUsers.customer()).with(csrf())
                .param("currentPassword", "old").param("password", "NewPassword123")
                .param("passwordConfirmation", "DifferentPassword123"))
                .andExpect(status().isOk()).andExpect(model().attribute("passwordError", true));
        verifyNoInteractions(service);
    }

    @Test void successEndsCurrentSessionAndOffersLogin() throws Exception {
        var id = InMemoryAuthenticationAccountStore.CUSTOMER_ID;
        when(service.change(id, 0, "old", "NewPassword123")).thenReturn(true);
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/account/password").session(session).with(SecurityTestUsers.customer()).with(csrf())
                .param("currentPassword", "old").param("password", "NewPassword123")
                .param("passwordConfirmation", "NewPassword123"))
                .andExpect(status().isOk()).andExpect(model().attribute("completed", true))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("/login")));
        org.assertj.core.api.Assertions.assertThat(session.isInvalid()).isTrue();
        verify(service).change(id, 0, "old", "NewPassword123");
    }
}
