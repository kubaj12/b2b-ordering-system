package io.github.kubaj12.online_store.identityaccess.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.identityaccess.domain.InvitationToken;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

import static org.assertj.core.api.Assertions.*;

class PasswordResetIntegrationTests extends PostgreSqlServiceTestSupport {
    @Autowired PasswordResetRequestService resets;
    @Autowired AccountAccessService access;
    @Autowired LoginAttemptService logins;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    private static final String PASSWORD = "ReplacementPassword12";

    private UUID account() {
        UUID id = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(id, "reset@example.test", "CUSTOMER", testClock().instant());
        return id;
    }

    private String request() {
        resets.request("reset@example.test");
        String link = mailDelivery().attempts().getLast().plainTextBody().split("\n\n")[1];
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private AccountPrincipal principal(UUID id) {
        return new AccountPrincipal(id, "reset@example.test", null, "CUSTOMER", "ACTIVE", 0);
    }

    @Test void replacementRevokesEverySessionAndOutstandingTokenAndRejectsOldLoginCompletion() {
        UUID id = account();
        String token = request();
        InvitationToken outstanding = InvitationToken.generate();
        Timestamp now = Timestamp.from(testClock().instant());
        jdbc.update("INSERT INTO identity_password_reset_token (id, user_id, token_hash, created_at, updated_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, outstanding.hash(), now, now, Timestamp.from(testClock().instant().plus(Duration.ofHours(1))));
        for (int i = 0; i < 2; i++) {
            jdbc.update("INSERT INTO identity_session (id, session_id_hash, user_id, security_version, created_at, updated_at, last_seen_at, expires_at) VALUES (?, ?, ?, 0, ?, ?, ?, ?)",
                    UUID.randomUUID(), InvitationToken.generate().hash(), id, now, now, now,
                    Timestamp.from(testClock().instant().plus(Duration.ofHours(2))));
        }
        assertThat(access.isCurrent(principal(id))).isTrue();
        assertThat(resets.reset(token, PASSWORD)).isTrue();
        assertThat(encoder.matches(PASSWORD, jdbc.queryForObject("SELECT password_hash FROM identity_user WHERE id = ?", String.class, id))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session WHERE user_id = ? AND revoked_at IS NOT NULL", Integer.class, id)).isEqualTo(2);
        assertThat(resets.reset(outstanding.value(), PASSWORD)).isFalse();
        assertThat(access.isCurrent(principal(id))).isFalse();
        assertThat(logins.completeSuccessfulLogin(principal(id), "127.0.0.1")).isFalse();
        assertThat(jdbc.queryForObject("SELECT security_version FROM identity_user WHERE id = ?", Long.class, id)).isEqualTo(1L);
    }

    @Test void concurrentConsumptionSucceedsExactlyOnce() throws Exception {
        UUID id = account();
        String token = request();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return resets.reset(token, PASSWORD); });
            var second = executor.submit(() -> { start.await(); return resets.reset(token, PASSWORD); });
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("SELECT security_version FROM identity_user WHERE id = ?", Long.class, id)).isEqualTo(1L);
    }

    @Test void rollbackRestoresPasswordAndTokenSoItCanBeUsedAgain() {
        UUID id = account();
        String token = request();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThat(resets.reset(token, PASSWORD)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT password_hash FROM identity_user WHERE id = ?", String.class, id))
                .isEqualTo(IdentityDatabaseFixture.TEST_PASSWORD_HASH);
        assertThat(jdbc.queryForObject("SELECT security_version FROM identity_user WHERE id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM identity_password_reset_token WHERE user_id = ?", Timestamp.class, id)).isNull();
        assertThat(resets.reset(token, PASSWORD)).isTrue();
    }
}
