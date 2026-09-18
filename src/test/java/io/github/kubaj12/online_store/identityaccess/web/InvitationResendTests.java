package io.github.kubaj12.online_store.identityaccess.web;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import io.github.kubaj12.online_store.identityaccess.application.InvitationService;
import io.github.kubaj12.online_store.identityaccess.domain.InvitationToken;
import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;
import io.github.kubaj12.online_store.testsupport.SecurityTestUsers;
import io.github.kubaj12.online_store.testsupport.InMemoryAuthenticationAccountStore;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@BrowserMvcTest(controllers = InvitationResendController.class)
class InvitationResendTests {
    @Autowired MockMvc mvc;
    @MockitoBean InvitationService service;
    @Test void anonymousCustomerAndCsrfFailureCannotResend() throws Exception {
        mvc.perform(get("/invitations/resend")).andExpect(status().isFound());
        mvc.perform(get("/invitations/resend").with(SecurityTestUsers.customer())).andExpect(status().isForbidden());
        mvc.perform(post("/invitations/resend").with(SecurityTestUsers.administrator())
                .param("invitationId", UUID.randomUUID().toString())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void administratorResendUsesActorAndRetainsReplacementIdForAnotherAttemptWithoutSecret() throws Exception {
        var previous = UUID.randomUUID();
        var replacement = new InvitationService.IssuedInvitation(UUID.randomUUID(), InvitationToken.generate());
        when(service.resend(previous, InMemoryAuthenticationAccountStore.ADMIN_ID)).thenReturn(replacement);
        mvc.perform(post("/invitations/resend").with(SecurityTestUsers.administrator()).with(csrf())
                .param("invitationId", previous.toString()))
                .andExpect(status().isOk()).andExpect(model().attribute("requested", true))
                .andExpect(model().attribute("invitationId", replacement.id()))
                .andExpect(content().string(not(containsString(replacement.token().value()))));
        verify(service).resend(previous, InMemoryAuthenticationAccountStore.ADMIN_ID);
    }
}
