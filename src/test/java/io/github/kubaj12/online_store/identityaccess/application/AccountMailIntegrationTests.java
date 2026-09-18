package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.kubaj12.online_store.identityaccess.domain.InvitationRole;
import io.github.kubaj12.online_store.identityaccess.domain.InvitationToken;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;
import static org.assertj.core.api.Assertions.*;

class AccountMailIntegrationTests extends PostgreSqlServiceTestSupport {
    @Autowired InvitationService invitations;
    @Autowired PasswordResetRequestService resets;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    private UUID account(String email, String role) {
        var id = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(id, email, role, testClock().instant());
        return id;
    }
    @Test void outerRollbackDiscardsInvitationAndMailAndCommitSendsExactlyOnce() {
        var admin = account("admin@example.test", "ADMIN");
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            invitations.issue("invited@example.test", InvitationRole.EMPLOYEE, admin);
            assertThat(mailDelivery().attempts()).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(mailDelivery().attempts()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_invitation", Integer.class)).isZero();
        var issued = invitations.issue("invited@example.test", InvitationRole.EMPLOYEE, admin);
        assertThat(mailDelivery().delivered()).hasSize(1);
        assertThat(mailDelivery().delivered().getFirst().plainTextBody()).contains(issued.token().value());
    }
    @Test void smtpFailureLeavesInvitationCommittedAndResendRotatesSecret() {
        var admin = account("admin@example.test", "ADMIN");
        mailDelivery().failNextPermanently();
        var first = invitations.issue("invited@example.test", InvitationRole.EMPLOYEE, admin);
        assertThat(mailDelivery().delivered()).isEmpty();
        var second = invitations.resend(first.id(), admin);
        assertThat(mailDelivery().delivered()).hasSize(1);
        assertThat(second.token().hash()).isNotEqualTo(first.token().hash());
        assertThatThrownBy(() -> invitations.accept(first.token().value(), "CorrectHorseBattery12"))
                .isInstanceOf(InvitationException.class);
    }
    @Test void genericResetRequestsExcludeUnknownAndBlockedAndRecoverFromFailure() {
        var id = account("active@example.test", "CUSTOMER");
        var blocked = account("blocked@example.test", "CUSTOMER");
        jdbc.update("UPDATE identity_user SET status = 'BLOCKED' WHERE id = ?", blocked);
        resets.request("unknown@example.test"); resets.request("blocked@example.test"); resets.request("invalid");
        assertThat(mailDelivery().attempts()).isEmpty();
        mailDelivery().failNextTemporarily();
        resets.request(" ACTIVE@EXAMPLE.TEST ");
        var first = tokenFromAttempt(0);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM identity_password_reset_token WHERE user_id = ?", byte[].class, id))
                .isEqualTo(InvitationToken.parse(first).hash());
        assertThat(mailDelivery().delivered()).isEmpty();
        resets.request("active@example.test");
        var replacement = tokenFromAttempt(1);
        assertThat(mailDelivery().delivered()).hasSize(1);
        assertThat(resets.reset(first, "CorrectHorseBattery12")).isFalse();
        assertThat(resets.reset(replacement, "CorrectHorseBattery12")).isTrue();
        assertThat(resets.reset(replacement, "AnotherCorrectPassword12")).isFalse();
        assertThat(jdbc.queryForObject("SELECT security_version FROM identity_user WHERE id = ?", Long.class, id)).isEqualTo(1L);
    }
    @Test void resetExpiresAtOneHourAndCannotUnblockAnAccount() {
        var id = account("active@example.test", "CUSTOMER");
        resets.request("active@example.test");
        var first = tokenFromAttempt(0);
        testClock().advance(Duration.ofHours(1));
        assertThat(resets.reset(first, "CorrectHorseBattery12")).isFalse();
        resets.request("active@example.test");
        jdbc.update("UPDATE identity_user SET status = 'BLOCKED' WHERE id = ?", id);
        assertThat(resets.reset(tokenFromAttempt(1), "CorrectHorseBattery12")).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM identity_user WHERE id = ?", String.class, id)).isEqualTo("BLOCKED");
    }
    private String tokenFromAttempt(int index) {
        var text = mailDelivery().attempts().get(index).plainTextBody();
        var link = text.split("\n\n")[1];
        return link.substring(link.lastIndexOf('/') + 1);
    }
}
