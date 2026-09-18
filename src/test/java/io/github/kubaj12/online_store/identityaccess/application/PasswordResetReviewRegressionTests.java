package io.github.kubaj12.online_store.identityaccess.application;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.kubaj12.online_store.notifications.application.AccountLinkMail;
import io.github.kubaj12.online_store.testsupport.*;
import static org.assertj.core.api.Assertions.*;

class PasswordResetReviewRegressionTests extends PostgreSqlServiceTestSupport {
    @Autowired PasswordResetRequestService resets;
    @Autowired PasswordResetRequestStore store;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired @Qualifier("accountLinkMailExecutor") ThreadPoolExecutor mailExecutor;
    private static final String PASSWORD = "CorrectHorseBattery12";
    private UUID account() {
        var id = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(id, "active@example.test", "CUSTOMER", testClock().instant());
        return id;
    }
    private String token() {
        String link = mailDelivery().attempts().getLast().plainTextBody().split("\n\n")[1];
        return link.substring(link.lastIndexOf('/') + 1);
    }
    private PasswordResetRequestService service(AccountLinkMail mail, PasswordEncoder passwordEncoder) {
        return new PasswordResetRequestService(store, mail, testClock(), manager, passwordEncoder);
    }
    private PasswordEncoder counting(AtomicInteger calls, Runnable duringHash) {
        return new PasswordEncoder() {
            public String encode(CharSequence raw) { calls.incrementAndGet(); duringHash.run(); return encoder.encode(raw); }
            public boolean matches(CharSequence raw, String hash) { return encoder.matches(raw, hash); }
        };
    }
    @Test void activeAndUnknownRequestsReturnWhileSmtpIsStillBlocked() throws Exception {
        account();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var mail = new AccountLinkMail(message -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Delivery timeout"); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { finished.countDown(); }
        }, URI.create("https://orders.example.test"), mailExecutor);
        var service = service(mail, encoder);
        try (var callers = Executors.newSingleThreadExecutor()) {
            try {
                var active = callers.submit(() -> service.request("active@example.test"));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                active.get(2, TimeUnit.SECONDS);
                callers.submit(() -> service.request("unknown@example.test")).get(2, TimeUnit.SECONDS);
                assertThat(finished.getCount()).isEqualTo(1L);
            } finally { release.countDown(); }
        }
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
    }
    @Test void saturatedQueueDoesNotEscapeRequestAndRepeatRequestRotatesToken() throws Exception {
        var id = account();
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        try {
            executor.execute(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> {}); // Fill its only queue slot.
            var rejectedMail = new AccountLinkMail(mailDelivery(), URI.create("https://orders.example.test"), executor);
            assertThatCode(() -> service(rejectedMail, encoder).request("active@example.test")).doesNotThrowAnyException();
            assertThat(mailDelivery().attempts()).isEmpty();
            byte[] rejectedHash = jdbc.queryForObject("SELECT token_hash FROM identity_password_reset_token WHERE user_id = ?", byte[].class, id);
            resets.request("active@example.test");
            assertThat(mailDelivery().delivered()).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT token_hash FROM identity_password_reset_token WHERE user_id = ? AND revoked_at IS NULL", byte[].class, id)).isNotEqualTo(rejectedHash);
            assertThat(resets.reset(token(), PASSWORD)).isTrue();
        } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    @ParameterizedTest @ValueSource(strings = {"consumed", "revoked", "expired", "blocked"})
    void ineligibleTokensNeverReachPasswordEncoder(String state) {
        var id = account(); resets.request("active@example.test");
        var token = token();
        switch (state) {
            case "consumed" -> assertThat(resets.reset(token, PASSWORD)).isTrue();
            case "revoked" -> resets.request("active@example.test");
            case "expired" -> testClock().advance(Duration.ofHours(1));
            case "blocked" -> jdbc.update("UPDATE identity_user SET status = 'BLOCKED' WHERE id = ?", id);
        }
        var calls = new AtomicInteger();
        var service = service(new AccountLinkMail(mailDelivery(), URI.create("https://orders.example.test"), Runnable::run), counting(calls, () -> {}));
        for (int i = 0; i < 3; i++) assertThat(service.reset(token, PASSWORD)).isFalse();
        assertThat(calls.get()).isZero();
    }
    @Test void expiryDuringHashingIsRecheckedWithoutChangingPassword() {
        var id = account(); resets.request("active@example.test"); var token = token();
        var calls = new AtomicInteger();
        var service = service(new AccountLinkMail(mailDelivery(), URI.create("https://orders.example.test"), Runnable::run),
                counting(calls, () -> testClock().advance(Duration.ofHours(1))));
        assertThat(service.reset(token, PASSWORD)).isFalse();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT security_version FROM identity_user WHERE id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM identity_password_reset_token WHERE user_id = ?", java.sql.Timestamp.class, id)).isNull();
    }
    @Test void expiryWhileWaitingForAccountLockRejectsBeforeHashing() throws Exception {
        var id = account(); resets.request("active@example.test"); var token = token();
        testClock().advance(Duration.ofHours(1).minusSeconds(1));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var service = service(new AccountLinkMail(mailDelivery(), URI.create("https://orders.example.test"), Runnable::run), counting(calls, () -> {}));
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var locker = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT id FROM identity_user WHERE id = ? FOR UPDATE", UUID.class, id);
                    held.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Lock timeout"); }
                    catch (InterruptedException exception) { throw new IllegalStateException("Lock interrupted"); }
                }));
                assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
                var reset = executor.submit(() -> service.reset(token, PASSWORD));
                boolean waiting = false;
                for (int i = 0; i < 200; i++) {
                    int count = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE 'SELECT id FROM identity_user WHERE id =%'", Integer.class);
                    if (count > 0) { waiting = true; break; }
                    Thread.sleep(10);
                }
                assertThat(waiting).isTrue();
                testClock().advance(Duration.ofSeconds(2));
                release.countDown(); locker.get(5, TimeUnit.SECONDS);
                assertThat(reset.get(5, TimeUnit.SECONDS)).isFalse();
                assertThat(calls.get()).isZero();
                assertThat(jdbc.queryForObject("SELECT consumed_at FROM identity_password_reset_token WHERE user_id = ?", java.sql.Timestamp.class, id)).isNull();
            } finally { release.countDown(); }
        }
    }
}
