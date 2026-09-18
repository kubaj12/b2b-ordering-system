package io.github.kubaj12.online_store.notifications.application;

import java.net.URI;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

/** Token mail exists only in memory until commit; rollback discards the handoff. No secret outbox. */
public final class AccountLinkMail {
    private final MailDelivery delivery;
    private final Executor executor;
    private final String baseUrl;
    public AccountLinkMail(MailDelivery delivery, URI baseUrl, Executor executor) {
        this.delivery = delivery;
        this.executor = executor;
        this.baseUrl = baseUrl.toASCIIString().replaceAll("/+$", "");
    }
    public void activationAfterCommit(String email, String token) {
        schedule(email, token, false);
    }
    public void resetAfterCommit(String email, String token) {
        schedule(email, token, true);
    }
    private void schedule(String email, String token, boolean reset) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Account link delivery requires an active token transaction");
        }
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid token");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    executor.execute(() -> send(email, token, reset));
                } catch (RejectedExecutionException ignored) {
                    // Saturation/shutdown leaves committed tokens recoverable through a new request/resend.
                }
            }
        });
    }
    private void send(String email, String token, boolean reset) {
        try {
            String link = baseUrl + (reset ? "/password-reset/" : "/invitations/accept/") + token;
            String subject = reset ? "Resetowanie hasła" : "Aktywacja konta";
            String instruction = reset ? "Aby ustawić nowe hasło, otwórz poniższy link. Link jest ważny przez godzinę."
                    : "Aby aktywować konto i ustawić hasło, otwórz poniższy link. Link jest ważny przez 7 dni.";
            String recovery = reset ? "Jeśli link wygasł lub wiadomość nie dotarła, ponownie poproś o reset hasła na stronie logowania."
                    : "Jeśli link wygasł lub wiadomość nie dotarła, poproś pracownika o ponowne wysłanie zaproszenia.";
            String text = instruction + "\n\n" + link + "\n\n" + recovery
                    + "\nJeśli nie oczekujesz tej wiadomości, zignoruj ją.";
            String html = "<!doctype html><html lang=\"pl\"><body><h1>" + subject + "</h1><p>"
                    + instruction + "</p><p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">"
                    + (reset ? "Ustaw nowe hasło" : "Aktywuj konto") + "</a></p><p>" + recovery
                    + "</p><p>Jeśli nie oczekujesz tej wiadomości, zignoruj ją.</p></body></html>";
            delivery.deliver(new OutgoingMail(email, subject, text, html));
        } catch (RuntimeException ignored) {
            // Committed tokens remain usable. Explicit resend/request rotates the token.
            // Never log provider diagnostics or retain mail for retry.
        }
    }
}
