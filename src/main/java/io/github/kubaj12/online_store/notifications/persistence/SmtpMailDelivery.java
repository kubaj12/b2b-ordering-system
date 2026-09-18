package io.github.kubaj12.online_store.notifications.persistence;

import io.github.kubaj12.online_store.notifications.application.*;
import jakarta.mail.MessagingException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** SMTP failures are sanitized: provider exceptions can retain entire token-bearing messages. */
public final class SmtpMailDelivery implements MailDelivery {
    private final JavaMailSender sender;
    private final String from;
    public SmtpMailDelivery(JavaMailSender sender, String from) { this.sender = sender; this.from = from; }
    @Override public void deliver(OutgoingMail mail) {
        try {
            var message = sender.createMimeMessage();
            message.getSession().setDebug(false);
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(mail.recipient());
            helper.setSubject(mail.subject());
            helper.setText(mail.plainTextBody(), mail.htmlBody());
            sender.send(message);
        } catch (MailAuthenticationException | MailParseException | MessagingException exception) {
            throw MailDeliveryException.permanent(null);
        } catch (MailException exception) {
            // Ambiguous send failures may include partial delivery; never retry the same bearer link automatically.
            throw MailDeliveryException.temporary(null);
        }
    }
}
