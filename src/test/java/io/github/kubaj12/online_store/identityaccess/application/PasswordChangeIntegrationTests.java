package io.github.kubaj12.online_store.identityaccess.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import io.github.kubaj12.online_store.identityaccess.domain.InvitationToken;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

import static org.assertj.core.api.Assertions.*;

class PasswordChangeIntegrationTests extends PostgreSqlServiceTestSupport {
    @Autowired PasswordChangeService changes;
    @Autowired AccountAccessService access;
    @Autowired LoginAttemptService logins;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;

    @Test void currentPasswordIsRequiredAndSuccessfulChangeRevokesSessionsAndResetTokens() {
        UUID id = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(id, "change@example.test", "CUSTOMER", testClock().instant());
        String oldPassword = "FixturePassword12";
        jdbc.update("UPDATE identity_user SET password_hash = ? WHERE id = ?", encoder.encode(oldPassword), id);
        Timestamp now = Timestamp.from(testClock().instant());
        jdbc.update("INSERT INTO identity_session (id, session_id_hash, user_id, security_version, created_at, updated_at, last_seen_at, expires_at) VALUES (?, ?, ?, 0, ?, ?, ?, ?)",
                UUID.randomUUID(), InvitationToken.generate().hash(), id, now, now, now,
                Timestamp.from(testClock().instant().plus(Duration.ofHours(2))));
        var token = InvitationToken.generate();
        jdbc.update("INSERT INTO identity_password_reset_token (id, user_id, token_hash, created_at, updated_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, token.hash(), now, now, Timestamp.from(testClock().instant().plus(Duration.ofHours(1))));
        var oldPrincipal = new AccountPrincipal(id, "change@example.test", null, "CUSTOMER", "ACTIVE", 0);
        assertThat(changes.change(id, 0, "wrong-password", "ReplacementPassword12")).isFalse();
        assertThat(jdbc.queryForObject("SELECT security_version FROM identity_user WHERE id = ?", Long.class, id)).isZero();
        assertThat(changes.change(id, 0, oldPassword, "ReplacementPassword12")).isTrue();
        assertThat(encoder.matches("ReplacementPassword12", jdbc.queryForObject("SELECT password_hash FROM identity_user WHERE id = ?", String.class, id))).isTrue();
        assertThat(jdbc.queryForObject("SELECT security_version FROM identity_user WHERE id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM identity_session WHERE user_id = ?", Timestamp.class, id)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM identity_password_reset_token WHERE user_id = ?", Timestamp.class, id)).isNotNull();
        assertThat(access.isCurrent(oldPrincipal)).isFalse();
        assertThat(logins.completeSuccessfulLogin(oldPrincipal, "127.0.0.1")).isFalse();
    }

    @Test void sessionThatPassedRequestValidationCannotChangePasswordAfterVersionRotation() {
        UUID id = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(id, "stale-change@example.test", "CUSTOMER", testClock().instant());
        String oldPassword = "CurrentPassword12";
        String originalHash = encoder.encode(oldPassword);
        jdbc.update("UPDATE identity_user SET password_hash = ? WHERE id = ?", originalHash, id);
        Timestamp now = Timestamp.from(testClock().instant());
        var token = InvitationToken.generate();
        jdbc.update("INSERT INTO identity_password_reset_token (id, user_id, token_hash, created_at, updated_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, token.hash(), now, now, Timestamp.from(testClock().instant().plus(Duration.ofHours(1))));

        // This is the version captured by the authenticated principal before a block/unblock boundary.
        long authenticatedVersion = 0;
        jdbc.update("UPDATE identity_user SET status = 'BLOCKED', security_version = security_version + 1 WHERE id = ?", id);
        jdbc.update("UPDATE identity_user SET status = 'ACTIVE' WHERE id = ?", id);

        assertThat(changes.change(id, authenticatedVersion, oldPassword, "ReplacementPassword12")).isFalse();
        assertThat(jdbc.queryForObject("SELECT password_hash FROM identity_user WHERE id = ?", String.class, id)).isEqualTo(originalHash);
        assertThat(jdbc.queryForObject("SELECT security_version FROM identity_user WHERE id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM identity_password_reset_token WHERE user_id = ?", Timestamp.class, id)).isNull();
    }
}
