package io.github.kubaj12.online_store.identityaccess.web;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import io.github.kubaj12.online_store.TestcontainersConfiguration;
import io.github.kubaj12.online_store.identityaccess.application.InvitationService;
import io.github.kubaj12.online_store.identityaccess.domain.InvitationRole;
import io.github.kubaj12.online_store.testsupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("postgresql")
@Import({TestcontainersConfiguration.class, DeterministicTestConfiguration.class})
class InvitationAcceptanceIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired InvitationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PostgreSqlDatabaseCleaner cleaner;
    @Autowired TestClock clock;
    UUID admin;
    @BeforeEach void clean() {
        cleaner.clean(); clock.reset(); admin = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(admin, "admin@example.test", "ADMIN", clock.instant());
    }
    @Test void onlyValidatedPostCreatesAccountAndStoredRoleWinsOverSubmittedRole() throws Exception {
        var issued = service.issue("invited@example.test", InvitationRole.EMPLOYEE, admin);
        String path = "/invitations/accept/" + issued.token().value();
        var before = jdbc.queryForMap("SELECT * FROM identity_invitation WHERE id = ?", issued.id());
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(head(path)).andExpect(status().isOk());
        mvc.perform(post(path).param("password", "CorrectHorseBattery12").param("passwordConfirmation", "CorrectHorseBattery12"))
                .andExpect(status().isFound());
        mvc.perform(post(path).with(csrf()).param("password", "short").param("passwordConfirmation", "short"))
                .andExpect(status().isOk()).andExpect(model().attribute("passwordError", true));
        assertThat(jdbc.queryForMap("SELECT * FROM identity_invitation WHERE id = ?", issued.id())).usingRecursiveComparison().isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_user", Integer.class)).isOne();
        mvc.perform(post(path).with(csrf()).param("password", "CorrectHorseBattery12").param("passwordConfirmation", "CorrectHorseBattery12")
                .param("role", "ADMIN").param("email", "attacker@example.test"))
                .andExpect(status().isOk()).andExpect(view().name("identityaccess/invitation-accepted"));
        assertThat(jdbc.queryForObject("SELECT role FROM identity_user WHERE email = 'invited@example.test'", String.class)).isEqualTo("EMPLOYEE");
        mvc.perform(post(path).with(csrf()).param("password", "CorrectHorseBattery12").param("passwordConfirmation", "CorrectHorseBattery12"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_user", Integer.class)).isEqualTo(2);
    }
    @Test void expiredGetDoesNotMutateLifecycleButPostCommitsExpiry() throws Exception {
        var issued = service.issue("expired@example.test", InvitationRole.EMPLOYEE, admin);
        clock.advance(Duration.ofDays(7));
        String path = "/invitations/accept/" + issued.token().value();
        mvc.perform(get(path)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM identity_invitation WHERE id = ?", String.class, issued.id())).isEqualTo("PENDING");
        mvc.perform(post(path).with(csrf()).param("password", "CorrectHorseBattery12").param("passwordConfirmation", "CorrectHorseBattery12"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM identity_invitation WHERE id = ?", String.class, issued.id())).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_user", Integer.class)).isOne();
    }
}
