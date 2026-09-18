package io.github.kubaj12.online_store.identityaccess.web;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import io.github.kubaj12.online_store.identityaccess.application.InvitationException;
import io.github.kubaj12.online_store.identityaccess.application.InvitationService;
import io.github.kubaj12.online_store.identityaccess.domain.InvitationToken;
import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@BrowserMvcTest(controllers = InvitationAcceptanceController.class)
class InvitationAcceptanceTests {
    @Autowired MockMvc mvc;
    @MockitoBean InvitationService service;
    private final String token = InvitationToken.generate().value();
    private final String password = "CorrectHorseBattery12";
    private String path() { return "/invitations/accept/" + token; }
    @Test void getAndHeadOnlyRenderPasswordFormAndCsrfToken() throws Exception {
        mvc.perform(get(path())).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("autocomplete=\"new-password\"")));
        mvc.perform(head(path())).andExpect(status().isOk());
        verifyNoInteractions(service);
    }
    @Test void postUsesOnlyTokenAndPasswordIgnoringRoleAndEmailTampering() throws Exception {
        when(service.accept(token, password)).thenReturn(UUID.randomUUID());
        mvc.perform(post(path()).with(csrf()).param("password", password).param("passwordConfirmation", password)
                .param("role", "ADMIN").param("email", "attacker@example.test"))
                .andExpect(status().isOk()).andExpect(view().name("identityaccess/invitation-accepted"))
                .andExpect(content().string(containsString("Konto zostało aktywowane")));
        verify(service).accept(token, password);
        verifyNoMoreInteractions(service);
    }
    @Test void mismatchedOrMissingPasswordsDoNotConsumeToken() throws Exception {
        mvc.perform(post(path()).with(csrf()).param("password", password).param("passwordConfirmation", "different"))
                .andExpect(status().isOk()).andExpect(model().attribute("passwordError", true));
        mvc.perform(post(path()).with(csrf()).param("password", password)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void invalidPasswordRendersFormWithoutRepopulatingSecret() throws Exception {
        when(service.accept(token, "short")).thenThrow(new IllegalArgumentException("password invalid"));
        mvc.perform(post(path()).with(csrf()).param("password", "short").param("passwordConfirmation", "short"))
                .andExpect(status().isOk()).andExpect(model().attribute("passwordError", true))
                .andExpect(model().attributeDoesNotExist("password", "passwordConfirmation"));
    }
    @Test void invalidInvitationUsesLocalizedConflictForFullPageAndHtmx() throws Exception {
        when(service.accept(token, password)).thenThrow(new InvitationException());
        for (String htmx : new String[] {"false", "true"}) {
            mvc.perform(post(path()).with(csrf()).header("HX-Request", htmx)
                    .param("password", password).param("passwordConfirmation", password))
                    .andExpect(status().isConflict()).andExpect(content().string(containsString("Zaproszenie jest nieważne")));
        }
    }
    @Test void missingOrInvalidCsrfBlocksOrdinaryAndHtmxPosts() throws Exception {
        mvc.perform(post(path()).param("password", password).param("passwordConfirmation", password))
                .andExpect(status().isFound());
        mvc.perform(post(path()).with(csrf().useInvalidToken()).param("password", password).param("passwordConfirmation", password))
                .andExpect(status().isFound());
        mvc.perform(post(path()).header("HX-Request", "true").param("password", password).param("passwordConfirmation", password))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
