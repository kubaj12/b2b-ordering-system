package io.github.kubaj12.online_store.identityaccess.web;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import io.github.kubaj12.online_store.TestcontainersConfiguration;
import io.github.kubaj12.online_store.testsupport.DeterministicTestConfiguration;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlDatabaseCleaner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("postgresql")
@Import({TestcontainersConfiguration.class, DeterministicTestConfiguration.class})
class BrowserAuthenticationIntegrationTests {

	private static final String PASSWORD = "KnownIntegrationPassword12";

	@Autowired private MockMvc mockMvc;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private PostgreSqlDatabaseCleaner databaseCleaner;

	@BeforeEach
	void clean() { databaseCleaner.clean(); }

	@Test
	void readsRealIdentityRowsForLoginAndPreservesLifecycleColumns() throws Exception {
		UUID id = UUID.randomUUID();
		Instant now = Instant.parse("2026-01-15T10:15:30Z");
		new IdentityDatabaseFixture(jdbcTemplate).insertUser(id, "real@example.test", passwordEncoder.encode(PASSWORD),
				"CUSTOMER", "ACTIVE", 0, now, now, null);
		mockMvc.perform(post("/login").with(csrf()).param("email", " REAL@EXAMPLE.TEST ").param("password", PASSWORD))
				.andExpect(status().isSeeOther()).andExpect(redirectedUrl("/"));
		assertThat(jdbcTemplate.queryForMap("SELECT role, status, security_version, last_login_at FROM identity_user WHERE id = ?", id))
				.containsEntry("role", "CUSTOMER").containsEntry("status", "ACTIVE")
				.containsEntry("security_version", 0L).containsEntry("last_login_at", null);
	}

	@Test
	void currentDatabaseStatusRevokesAStoredServletSession() throws Exception {
		UUID id = UUID.randomUUID();
		Instant now = Instant.parse("2026-01-15T10:15:30Z");
		new IdentityDatabaseFixture(jdbcTemplate).insertUser(id, "state@example.test", passwordEncoder.encode(PASSWORD),
				"CUSTOMER", "ACTIVE", 0, now, now, null);
		var login = mockMvc.perform(post("/login").with(csrf()).param("email", "state@example.test").param("password", PASSWORD))
				.andExpect(status().isSeeOther()).andReturn();
		jdbcTemplate.update("UPDATE identity_user SET status = 'BLOCKED', updated_at = ? WHERE id = ?",
				java.time.OffsetDateTime.ofInstant(now.plusSeconds(1), java.time.ZoneOffset.UTC), id);
		mockMvc.perform(get("/").session((org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false)))
				.andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
	}
}
