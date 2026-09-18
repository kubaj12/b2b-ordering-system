package io.github.kubaj12.online_store.identityaccess.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import io.github.kubaj12.online_store.identityaccess.application.PasswordResetRequestService;
import io.github.kubaj12.online_store.testsupport.BrowserMvcTest;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@BrowserMvcTest(controllers = PasswordResetRequestController.class)
class PasswordResetRequestTests {
    @Autowired MockMvc mvc;
    @MockitoBean PasswordResetRequestService service;
    @Test void requestIsAnonymousGenericAndRepeatableForOrdinaryAndHtmxRequests() throws Exception {
        mvc.perform(get("/password-reset")).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        for (String email : new String[]{"active@example.test", "unknown@example.test", "blocked@example.test"}) {
            mvc.perform(post("/password-reset").with(csrf()).header("HX-Request", "true").param("email", email))
                    .andExpect(status().isOk()).andExpect(model().attribute("requested", true))
                    .andExpect(content().string(containsString("Jeśli adres jest przypisany do aktywnego konta")))
                    .andExpect(content().string(not(containsString(email))));
            verify(service).request(email);
        }
    }
    @Test void csrfAndGetCannotIssueOrConsumeTokens() throws Exception {
        mvc.perform(post("/password-reset").param("email", "active@example.test")).andExpect(status().isFound());
        mvc.perform(post("/password-reset").with(csrf().useInvalidToken()).header("HX-Request", "true")
                .param("email", "active@example.test")).andExpect(status().isUnauthorized());
        mvc.perform(get("/password-reset/" + "A".repeat(43))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        verifyNoInteractions(service);
    }
    @Test void invalidTokenHasUsableNewRequestLinkAndPasswordsAreNotRepopulated() throws Exception {
        mvc.perform(post("/password-reset/" + "A".repeat(43)).with(csrf())
                .param("password", "CorrectHorseBattery12").param("passwordConfirmation", "CorrectHorseBattery12"))
                .andExpect(status().isOk()).andExpect(model().attribute("resetError", true))
                .andExpect(model().attributeDoesNotExist("password", "passwordConfirmation"))
                .andExpect(content().string(containsString("href=\"/password-reset\"")));
    }
    @Test void successfulResetShowsLoginAndRemovesPasswordForm() throws Exception {
        String token = "A".repeat(43);
        when(service.reset(token, "CorrectHorseBattery12")).thenReturn(true);
        mvc.perform(post("/password-reset/" + token).with(csrf())
                .param("password", "CorrectHorseBattery12").param("passwordConfirmation", "CorrectHorseBattery12"))
                .andExpect(status().isOk()).andExpect(model().attribute("completed", true))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("href=\"/login\"")))
                .andExpect(content().string(not(containsString("name=\"password\""))));
    }
    @Test void mismatchedPasswordsCannotConsumeToken() throws Exception {
        mvc.perform(post("/password-reset/" + "A".repeat(43)).with(csrf())
                .param("password", "CorrectHorseBattery12").param("passwordConfirmation", "DifferentPassword12"))
                .andExpect(status().isOk()).andExpect(model().attribute("resetError", true));
        verifyNoInteractions(service);
    }
}
