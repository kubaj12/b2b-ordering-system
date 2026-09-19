package io.github.kubaj12.online_store.identityaccess.application;

import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.kubaj12.online_store.testsupport.*;
import static org.assertj.core.api.Assertions.*;

class AccountStatusIntegrationTests extends PostgreSqlServiceTestSupport {
    @Autowired AccountStatusService statuses;
    @Autowired AccountStatusStore store;
    @Autowired AccountAccessService access;
    @Autowired LoginAttemptService logins;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    UUID admin, employee, customer;
    @BeforeEach void users() {
        admin = insert("ADMIN", "admin"); employee = insert("EMPLOYEE", "employee"); customer = insert("CUSTOMER", "customer");
    }
    private UUID insert(String role, String name) {
        UUID id = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(id, name + "@example.test", role, testClock().instant());
        return id;
    }
    private AccountPrincipal principal() { return new AccountPrincipal(customer, "customer@example.test", null, "CUSTOMER", "ACTIVE", 0); }
    @Test void enforcesCompleteRoleMatrixBeforeIdempotency() {
        for (UUID actor : new UUID[]{admin, employee, customer, UUID.randomUUID()}) {
            for (UUID target : new UUID[]{admin, employee, customer, UUID.randomUUID()}) {
                boolean allowed = actor.equals(admin) && (target.equals(employee) || target.equals(customer))
                    || actor.equals(employee) && target.equals(customer);
                if (allowed) { statuses.block(actor, target); statuses.unblock(actor, target); }
                else {
                    assertThatThrownBy(() -> statuses.block(actor, target)).isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> statuses.unblock(actor, target)).isInstanceOf(AccessDeniedException.class);
                }
            }
        }
        jdbc.update("UPDATE identity_user SET status = 'BLOCKED' WHERE id = ?", employee);
        assertThatThrownBy(() -> statuses.unblock(employee, customer)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void revokesRegisteredSessionsAndDoesNotRestoreVersionAfterUnblock() {
        assertThat(logins.completeSuccessfulLogin(principal(), "127.0.0.1", "opaque-session", 1800)).isTrue();
        statuses.block(employee, customer); statuses.block(employee, customer);
        statuses.unblock(admin, customer); statuses.unblock(admin, customer);
        assertThat(access.isCurrent(principal())).isFalse();
        assertThat(logins.completeSuccessfulLogin(principal(), "127.0.0.1", "stale-login", 1800)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session WHERE user_id = ? AND revoked_at IS NOT NULL", Integer.class, customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT acting_user_id FROM audit_event", UUID.class)).containsExactlyInAnyOrder(employee, admin);
        assertThat(jdbc.queryForObject("SELECT security_version FROM identity_user WHERE id = ?", Long.class, customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session", Integer.class)).isEqualTo(1);
    }
    @Test void auditFailureRollsBackStatusVersionAndSessionRevocation() {
        logins.completeSuccessfulLogin(principal(), "127.0.0.1", "rollback-session", 1800);
        var failing = new AccountStatusService(store, event -> { throw new IllegalStateException("audit unavailable"); }, testClock());
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> failing.block(employee, customer))).isInstanceOf(IllegalStateException.class);
        assertThat(access.isCurrent(principal())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session WHERE revoked_at IS NOT NULL", Integer.class)).isZero();
    }
    @Test void concurrentLoginWaitsForBlockAndCannotRegisterStaleVersion() throws Exception {
        var blocked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var block = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                statuses.block(employee, customer);
                blocked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
            var login = executor.submit(() -> logins.completeSuccessfulLogin(principal(), "127.0.0.1", "racing-session", 1800));
            try {
                assertThatThrownBy(() -> login.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            block.get(5, TimeUnit.SECONDS);
            assertThat(login.get(5, TimeUnit.SECONDS)).isFalse();
        }
        statuses.unblock(admin, customer);
        assertThat(access.isCurrent(principal())).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_session", Integer.class)).isZero();
    }
}
