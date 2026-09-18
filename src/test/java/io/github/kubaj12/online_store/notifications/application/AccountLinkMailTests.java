package io.github.kubaj12.online_store.notifications.application;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class AccountLinkMailTests {
    private final java.util.List<OutgoingMail> sent = new java.util.ArrayList<>();
    private final AccountLinkMail mail = new AccountLinkMail(sent::add, URI.create("https://orders.example.test/b2b/"), Runnable::run);
    private final String token = "A".repeat(43);
    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); }
    private void begin() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
    @Test void refusesDeliveryOutsideTokenTransaction() {
        assertThatThrownBy(() -> mail.activationAfterCommit("user@example.test", token)).isInstanceOf(IllegalStateException.class);
        assertThat(sent).isEmpty();
    }
    @Test void activationIsOnlyRenderedAndDeliveredAfterCommitAndUsesConfiguredPath() {
        begin();
        mail.activationAfterCommit("user@example.test", token);
        assertThat(sent).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        assertThat(sent).hasSize(1);
        var message = sent.getFirst();
        assertThat(message.subject()).isEqualTo("Aktywacja konta");
        assertThat(message.plainTextBody()).contains("https://orders.example.test/b2b/invitations/accept/" + token, "7 dni", "ponowne");
        assertThat(message.htmlBody()).contains("lang=\"pl\"", "Aktywuj konto");
        assertThat(message.toString()).doesNotContain(token);
    }
    @Test void rollbackNeverSendsAndResetCanBeRequestedAgain() {
        begin();
        mail.resetAfterCommit("user@example.test", token);
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(1));
        assertThat(sent).isEmpty();
    }
    @Test void resetTemplateAndFailureDoNotEscapeCommittedTransaction() {
        begin();
        var failing = new AccountLinkMail(message -> {
            assertThat(message.plainTextBody()).contains("/password-reset/" + token, "godzinę", "ponownie");
            assertThat(message.htmlBody()).contains("Ustaw nowe hasło");
            throw MailDeliveryException.temporary(null);
        }, URI.create("https://orders.example.test"), Runnable::run);
        failing.resetAfterCommit("user@example.test", token);
        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit())).doesNotThrowAnyException();
    }
}
