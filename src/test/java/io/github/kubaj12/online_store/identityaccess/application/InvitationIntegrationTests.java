package io.github.kubaj12.online_store.identityaccess.application;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import io.github.kubaj12.online_store.identityaccess.domain.InvitationRole;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;
import static org.assertj.core.api.Assertions.*;

class InvitationIntegrationTests extends PostgreSqlServiceTestSupport {
    private static final String EMAIL = "invited@example.test";
    private static final String PASSWORD = "CorrectHorseBattery12";
    @Autowired InvitationService service;
    @Autowired InvitationStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager manager;
    UUID admin;
    @BeforeEach void actor() {
        admin = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(admin, "admin@example.test", "ADMIN", testClock().instant());
    }
    @Test void storesOnlyDigestAndAcceptsExactlyOnceWithStoredRole() {
        testClock().set(testClock().instant().plusNanos(789));
        var issued = service.issue(" INVITED@EXAMPLE.TEST ", InvitationRole.EMPLOYEE, admin);
        var row = jdbc.queryForMap("SELECT * FROM identity_invitation WHERE id = ?", issued.id());
        assertThat(row.get("email")).isEqualTo(EMAIL);
        assertThat((byte[]) row.get("token_hash")).isEqualTo(issued.token().hash());
        assertThat(((Timestamp) row.get("expires_at")).toInstant())
                .isEqualTo(((Timestamp) row.get("created_at")).toInstant().plus(Duration.ofDays(7)));
        UUID account = service.accept(issued.token().value(), PASSWORD);
        var user = jdbc.queryForMap("SELECT * FROM identity_user WHERE id = ?", account);
        assertThat(user.get("role")).isEqualTo("EMPLOYEE");
        assertThat(user.get("status")).isEqualTo("ACTIVE");
        assertThat(encoder.matches(PASSWORD, (String) user.get("password_hash"))).isTrue();
        assertThat(state(issued)).isEqualTo("ACCEPTED");
        assertThatThrownBy(() -> service.accept(issued.token().value(), PASSWORD)).isInstanceOf(InvitationException.class);
        assertThatThrownBy(() -> service.resend(issued.id(), admin)).isInstanceOf(InvitationException.class);
        assertThat(users()).isOne();
    }
    @Test void expiryAtSevenDaysIsCommittedAndCanBeResent() {
        var issued = service.issue(EMAIL, InvitationRole.EMPLOYEE, admin);
        testClock().advance(Duration.ofDays(7));
        assertThatThrownBy(() -> service.accept(issued.token().value(), PASSWORD)).isInstanceOf(InvitationException.class);
        assertThat(state(issued)).isEqualTo("EXPIRED");
        assertThat(users()).isZero();
        var replacement = service.resend(issued.id(), admin);
        assertThat(state(replacement)).isEqualTo("PENDING");
        assertThatThrownBy(() -> service.resend(issued.id(), admin)).isInstanceOf(InvitationException.class);
        assertThat(state(replacement)).isEqualTo("PENDING");
    }
    @Test void justBeforeExpiryIsValid() {
        var issued = service.issue(EMAIL, InvitationRole.EMPLOYEE, admin);
        testClock().advance(Duration.ofDays(7).minusNanos(1000));
        service.accept(issued.token().value(), PASSWORD);
        assertThat(state(issued)).isEqualTo("ACCEPTED");
    }
    @Test void resendRevokesOldTokenPreservesRoleAndAllowsOnlyNewToken() {
        var issued = service.issue(EMAIL, InvitationRole.EMPLOYEE, admin);
        var replacement = service.resend(issued.id(), admin);
        assertThat(replacement.token().value()).isNotEqualTo(issued.token().value());
        assertThat(state(issued)).isEqualTo("REVOKED");
        assertThatThrownBy(() -> service.accept(issued.token().value(), PASSWORD)).isInstanceOf(InvitationException.class);
        assertThatThrownBy(() -> service.resend(issued.id(), admin)).isInstanceOf(InvitationException.class);
        var account = service.accept(replacement.token().value(), PASSWORD);
        assertThat(jdbc.queryForObject("SELECT role FROM identity_user WHERE id = ?", String.class, account)).isEqualTo("EMPLOYEE");
    }
    @Test void pendingUniquenessSpansRolesAndNormalizationAndExpiredIssuanceFreesEmail() {
        var issued = service.issue(EMAIL, InvitationRole.CUSTOMER, admin);
        assertThatThrownBy(() -> service.issue(" INVITED@EXAMPLE.TEST ", InvitationRole.EMPLOYEE, admin))
                .isInstanceOf(InvitationException.class);
        testClock().advance(Duration.ofDays(7));
        var fresh = service.issue(EMAIL, InvitationRole.EMPLOYEE, admin);
        assertThat(state(issued)).isEqualTo("EXPIRED");
        assertThat(state(fresh)).isEqualTo("PENDING");
    }
    @Test void validatesPasswordsAndRejectsExistingAccountsAndUnauthorizedActors() {
        var issued = service.issue(EMAIL, InvitationRole.EMPLOYEE, admin);
        assertThatThrownBy(() -> service.accept(issued.token().value(), "short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.accept("invalid-token", PASSWORD)).isInstanceOf(InvitationException.class);
        assertThat(state(issued)).isEqualTo("PENDING");
        new IdentityDatabaseFixture(jdbc).insertActiveUser(UUID.randomUUID(), EMAIL, "CUSTOMER", testClock().instant());
        assertThatThrownBy(() -> service.accept(issued.token().value(), PASSWORD)).isInstanceOf(InvitationException.class);
        assertThatThrownBy(() -> service.resend(issued.id(), admin)).isInstanceOf(InvitationException.class);
        assertThatThrownBy(() -> service.issue(EMAIL, InvitationRole.CUSTOMER, admin)).isInstanceOf(InvitationException.class);
        UUID employee = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(employee, "employee@example.test", "EMPLOYEE", testClock().instant());
        assertThatThrownBy(() -> service.issue("other@example.test", InvitationRole.EMPLOYEE, employee)).isInstanceOf(AccessDeniedException.class);
        service.issue("other@example.test", InvitationRole.CUSTOMER, employee);
        jdbc.update("UPDATE identity_user SET status = 'BLOCKED' WHERE id = ?", employee);
        assertThatThrownBy(() -> service.issue("blocked@example.test", InvitationRole.CUSTOMER, employee)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void failedReplacementRollsBackRevocationAndFailedAcceptanceRollsBackAccount() {
        var issued = service.issue(EMAIL, InvitationRole.EMPLOYEE, admin);
        assertThatThrownBy(() -> failingService("insert").resend(issued.id(), admin)).isInstanceOf(IllegalStateException.class);
        assertThat(state(issued)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_invitation", Integer.class)).isOne();
        assertThatThrownBy(() -> failingService("accept").accept(issued.token().value(), PASSWORD)).isInstanceOf(IllegalStateException.class);
        assertThat(state(issued)).isEqualTo("PENDING");
        assertThat(users()).isZero();
        service.accept(issued.token().value(), PASSWORD);
    }
    @Test void competingRolesCreateOnlyOnePendingInvitation() throws Exception {
        var outcomes = race(() -> service.issue(EMAIL, InvitationRole.CUSTOMER, admin),
                () -> service.issue(" INVITED@EXAMPLE.TEST ", InvitationRole.EMPLOYEE, admin));
        assertThat(outcomes.stream().filter(InvitationException.class::isInstance).count()).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_invitation WHERE email = ? AND status = 'PENDING'", Integer.class, EMAIL)).isOne();
    }
    @Test void competingAcceptancesCreateExactlyOneAccount() throws Exception {
        var issued = service.issue(EMAIL, InvitationRole.EMPLOYEE, admin);
        var outcomes = race(() -> service.accept(issued.token().value(), PASSWORD), () -> service.accept(issued.token().value(), PASSWORD));
        assertThat(outcomes.stream().filter(UUID.class::isInstance).count()).isOne();
        assertThat(outcomes.stream().filter(InvitationException.class::isInstance).count()).isOne();
        assertThat(users()).isOne();
        assertThat(state(issued)).isEqualTo("ACCEPTED");
    }
    @Test void acceptanceAndResendHaveOnlySerializedOutcomes() throws Exception {
        var issued = service.issue(EMAIL, InvitationRole.EMPLOYEE, admin);
        var outcomes = race(() -> service.accept(issued.token().value(), PASSWORD), () -> service.resend(issued.id(), admin));
        assertThat(outcomes.stream().filter(InvitationException.class::isInstance).count()).isOne();
        if (state(issued).equals("ACCEPTED")) {
            assertThat(users()).isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_invitation WHERE status = 'PENDING'", Integer.class)).isZero();
        } else {
            assertThat(state(issued)).isEqualTo("REVOKED");
            assertThat(users()).isZero();
            var replacement = (InvitationService.IssuedInvitation) outcomes.get(1);
            service.accept(replacement.token().value(), PASSWORD);
            assertThat(users()).isOne();
        }
    }
    @Test void competingResendsIssueOnlyOneReplacement() throws Exception {
        var issued = service.issue(EMAIL, InvitationRole.EMPLOYEE, admin);
        var outcomes = race(() -> service.resend(issued.id(), admin), () -> service.resend(issued.id(), admin));
        assertThat(outcomes.stream().filter(InvitationService.IssuedInvitation.class::isInstance).count()).isOne();
        assertThat(outcomes.stream().filter(InvitationException.class::isInstance).count()).isOne();
        assertThat(state(issued)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_invitation WHERE status = 'PENDING'", Integer.class)).isOne();
    }
    @Test void acceptanceHoldingEmailLockWinsBeforeQueuedResend() throws Exception {
        assertOrderedRace(true);
    }
    @Test void resendHoldingEmailLockRevokesTokenBeforeQueuedAcceptance() throws Exception {
        assertOrderedRace(false);
    }
    private void assertOrderedRace(boolean acceptanceFirst) throws Exception {
        var issued = service.issue(EMAIL, InvitationRole.EMPLOYEE, admin);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondAttempt = new CountDownLatch(1);
        InvitationService firstService = lockingService(acquired, release, null);
        InvitationService secondService = lockingService(null, null, secondAttempt);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> acceptanceFirst
                    ? firstService.accept(issued.token().value(), PASSWORD) : firstService.resend(issued.id(), admin));
            try {
                assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    try {
                        return acceptanceFirst ? secondService.resend(issued.id(), admin)
                                : secondService.accept(issued.token().value(), PASSWORD);
                    } catch (InvitationException exception) { return exception; }
                });
                assertThat(secondAttempt.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(second.isDone()).isFalse();
                release.countDown();
                assertThat(second.get(30, TimeUnit.SECONDS)).isInstanceOf(InvitationException.class);
                Object winner = first.get(30, TimeUnit.SECONDS);
                assertThat(state(issued)).isEqualTo(acceptanceFirst ? "ACCEPTED" : "REVOKED");
                assertThat(users()).isEqualTo(acceptanceFirst ? 1 : 0);
                if (!acceptanceFirst) {
                    service.accept(((InvitationService.IssuedInvitation) winner).token().value(), PASSWORD);
                    assertThat(users()).isOne();
                }
            } finally { release.countDown(); }
        }
    }
    private InvitationService lockingService(CountDownLatch acquired, CountDownLatch release, CountDownLatch attempted) {
        InvitationStore gated = (InvitationStore) Proxy.newProxyInstance(InvitationStore.class.getClassLoader(), new Class<?>[] {InvitationStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("lockEmail") && attempted != null) attempted.countDown();
                    try {
                        Object result = method.invoke(store, args);
                        if (method.getName().equals("lockEmail") && acquired != null) {
                            acquired.countDown();
                            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("lock gate timed out");
                        }
                        return result;
                    } catch (InvocationTargetException exception) { throw exception.getCause(); }
                });
        return new InvitationService(gated, encoder, testClock(), manager);
    }
    @Test void expiryDuringPasswordHashingIsCommittedWithNoPartialAccount() {
        var issued = service.issue(EMAIL, InvitationRole.EMPLOYEE, admin);
        PasswordEncoder expiryEncoder = new PasswordEncoder() {
            @Override public String encode(CharSequence password) {
                String hash = encoder.encode(password);
                testClock().advance(Duration.ofDays(7));
                return hash;
            }
            @Override public boolean matches(CharSequence password, String hash) {
                return encoder.matches(password, hash);
            }
        };
        var expiringService = new InvitationService(store, expiryEncoder, testClock(), manager);
        assertThatThrownBy(() -> expiringService.accept(issued.token().value(), PASSWORD)).isInstanceOf(InvitationException.class);
        assertThat(state(issued)).isEqualTo("EXPIRED");
        assertThat(users()).isZero();
        var replacement = service.resend(issued.id(), admin);
        service.accept(replacement.token().value(), PASSWORD);
        assertThat(users()).isOne();
    }
    private String state(InvitationService.IssuedInvitation issued) {
        return jdbc.queryForObject("SELECT status FROM identity_invitation WHERE id = ?", String.class, issued.id());
    }
    private int users() { return jdbc.queryForObject("SELECT COUNT(*) FROM identity_user WHERE email = ?", Integer.class, EMAIL); }
    private InvitationService failingService(String methodName) {
        InvitationStore failing = (InvitationStore) Proxy.newProxyInstance(InvitationStore.class.getClassLoader(), new Class<?>[] {InvitationStore.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(store, args);
                        if (method.getName().equals(methodName)) throw new IllegalStateException("synthetic failure");
                        return result;
                    } catch (InvocationTargetException exception) { throw exception.getCause(); }
                });
        return new InvitationService(failing, encoder, testClock(), manager);
    }
    private List<Object> race(Supplier<?> first, Supplier<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> run(first, ready, start));
            var b = executor.submit(() -> run(second, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally { start.countDown(); }
    }
    private Object run(Supplier<?> operation, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race start timed out");
        try { return operation.get(); } catch (InvitationException exception) { return exception; }
    }
}
