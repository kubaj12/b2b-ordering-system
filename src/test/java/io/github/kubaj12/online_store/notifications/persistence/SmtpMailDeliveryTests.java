package io.github.kubaj12.online_store.notifications.persistence;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import io.github.kubaj12.online_store.notifications.application.*;
import static org.assertj.core.api.Assertions.*;

class SmtpMailDeliveryTests {
    private static class Sender extends JavaMailSenderImpl {
        MimeMessage captured;
        RuntimeException failure;
        @Override public void send(MimeMessage message) {
            captured = message;
            if (failure != null) throw failure;
        }
    }
    private final OutgoingMail mail = new OutgoingMail("user@example.test", "Aktywacja konta", "Tajny link", "<p>Tajny link</p>");
    @Test void sendsConfiguredSenderRecipientAndUtf8Alternatives() throws Exception {
        var sender = new Sender();
        sender.getSession().setDebug(true);
        new SmtpMailDelivery(sender, "support@example.test").deliver(mail);
        assertThat(sender.getSession().getDebug()).isFalse();
        assertThat(sender.captured.getFrom()[0].toString()).isEqualTo("support@example.test");
        assertThat(sender.captured.getAllRecipients()[0].toString()).isEqualTo(mail.recipient());
        assertThat(sender.captured.getSubject()).isEqualTo(mail.subject());
        sender.captured.saveChanges();
        var bytes = new java.io.ByteArrayOutputStream();
        sender.captured.writeTo(bytes);
        assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("multipart/alternative", "charset=UTF-8");
    }
    @Test void failureCannotRetainTokenBearingProviderException() {
        var sender = new Sender();
        sender.failure = new MailSendException("raw-secret-link");
        assertThatThrownBy(() -> new SmtpMailDelivery(sender, "support@example.test").deliver(mail))
                .isInstanceOf(MailDeliveryException.class).hasCause(null).hasMessageNotContaining("raw-secret-link");
        sender.failure = new MailAuthenticationException("smtp-password");
        try { new SmtpMailDelivery(sender, "support@example.test").deliver(mail); }
        catch (MailDeliveryException failure) {
            assertThat(failure.kind()).isEqualTo(MailDeliveryException.Kind.PERMANENT);
            assertThat(failure.getCause()).isNull();
        }
    }
}
