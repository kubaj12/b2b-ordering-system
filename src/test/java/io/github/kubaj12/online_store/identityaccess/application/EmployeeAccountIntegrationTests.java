package io.github.kubaj12.online_store.identityaccess.application;

import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import io.github.kubaj12.online_store.identityaccess.domain.InvitationRole;
import io.github.kubaj12.online_store.testsupport.*;
import static org.assertj.core.api.Assertions.*;

class EmployeeAccountIntegrationTests extends PostgreSqlServiceTestSupport {
    @Autowired EmployeeAccountService accounts;
    @Autowired InvitationService invitations;
    @Autowired AccountAccessService access;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmployeeAccountStore store;
    @Autowired InvitationStore invitationStore;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired io.github.kubaj12.online_store.notifications.application.AccountLinkMail mail;
    UUID admin;
    UUID employee;
    @BeforeEach void actors() {
        admin = UUID.randomUUID(); employee = UUID.randomUUID();
        var fixture = new IdentityDatabaseFixture(jdbc);
        fixture.insertActiveUser(admin, "admin@example.test", "ADMIN", testClock().instant());
        fixture.insertActiveUser(employee, "employee@example.test", "EMPLOYEE", testClock().instant());
    }
    @Test void directoryAndInvitationLifecycleCarryActorsWithoutSecrets() {
        var issued = invitations.issue(" New@Example.Test ", InvitationRole.EMPLOYEE, admin);
        assertThat(accounts.directory(admin).staff()).hasSize(2);
        assertThat(mailDelivery().delivered()).hasSize(1);
        assertThat(accounts.directory(admin).invitations()).extracting(EmployeeAccountStore.Pending::email).containsExactly("new@example.test");
        var replacement = invitations.resend(issued.id(), admin);
        UUID activated = invitations.accept(replacement.token().value(), "CorrectHorseBattery12");
        assertThat(accounts.directory(admin).staff()).hasSize(3);
        assertThat(accounts.directory(admin).invitations()).isEmpty();
        assertThat(jdbc.queryForList("SELECT acting_user_id FROM audit_event WHERE event_type IN ('identity.invitation.issued', 'identity.invitation.revoked')", UUID.class)).containsOnly(admin);
        assertThat(jdbc.queryForObject("SELECT acting_user_id FROM audit_event WHERE event_type = 'identity.invitation.accepted'", UUID.class)).isEqualTo(activated);
        assertThat(jdbc.queryForList("SELECT change_metadata::text FROM audit_event", String.class)).allMatch(value -> !value.contains(replacement.token().value()));
    }
    @Test void blockingRotatesVersionAndUnblockNeverRestoresOldAccessWithIdempotentAudits() {
        var old = new AccountPrincipal(employee, "employee@example.test", null, "EMPLOYEE", "ACTIVE", 0);
        assertThat(access.isCurrent(old)).isTrue();
        accounts.block(admin, employee); accounts.block(admin, employee);
        assertThat(access.isCurrent(old)).isFalse();
        accounts.unblock(admin, employee); accounts.unblock(admin, employee);
        assertThat(access.isCurrent(old)).isFalse();
        assertThat(access.isCurrent(new AccountPrincipal(employee, "employee@example.test", null, "EMPLOYEE", "ACTIVE", 1))).isTrue();
        assertThat(jdbc.queryForList("SELECT acting_user_id FROM audit_event WHERE event_type = 'identity.account.status_changed'", UUID.class)).containsExactly(admin, admin);
        assertThat(jdbc.queryForObject("SELECT change_metadata->'status'->>'from' FROM audit_event WHERE change_metadata->'status'->>'to' = 'BLOCKED'", String.class)).isEqualTo("ACTIVE");
    }
    @Test void auditFailureRollsBackStatusAndInvitationAndSuppressesMail() {
        io.github.kubaj12.online_store.shared.auditing.AuditEventRecorder failing = event -> { throw new IllegalStateException("audit unavailable"); };
        var accountService = new EmployeeAccountService(store, failing, testClock());
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> accountService.block(admin, employee)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM identity_user WHERE id = ?", String.class, employee)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT security_version FROM identity_user WHERE id = ?", Long.class, employee)).isZero();
        var invitationService = new InvitationService(invitationStore, encoder, testClock(), manager, mail, failing);
        assertThatThrownBy(() -> invitationService.inviteEmployee("failed@example.test", admin)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_invitation", Integer.class)).isZero();
        assertThat(mailDelivery().delivered()).isEmpty();
    }
    @Test void rejectsNonAdministratorsBlockedActorsAndNonEmployeeTargets() {
        var customer = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(customer, "customer@example.test", "CUSTOMER", testClock().instant());
        for (UUID actor : new UUID[] {employee, customer, UUID.randomUUID()}) {
            assertThatThrownBy(() -> accounts.directory(actor)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> accounts.block(actor, employee)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> accounts.unblock(actor, employee)).isInstanceOf(AccessDeniedException.class);
        }
        for (UUID target : new UUID[] {admin, customer, UUID.randomUUID()})
            assertThatThrownBy(() -> accounts.block(admin, target)).isInstanceOf(AccessDeniedException.class);
        jdbc.update("UPDATE identity_user SET status = 'BLOCKED' WHERE id = ?", admin);
        assertThatThrownBy(() -> accounts.block(admin, employee)).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isZero();
    }
}
