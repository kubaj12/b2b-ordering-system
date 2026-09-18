package io.github.kubaj12.online_store.identityaccess.application;

import io.github.kubaj12.online_store.notifications.application.AccountLinkMail;
import java.time.Clock;
import io.github.kubaj12.online_store.shared.auditing.AuditActor;
import io.github.kubaj12.online_store.shared.auditing.AuditEventRecorder;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.kubaj12.online_store.identityaccess.domain.InvitationRole;
import io.github.kubaj12.online_store.identityaccess.domain.InvitationToken;
import io.github.kubaj12.online_store.identityaccess.domain.NormalizedEmail;
import io.github.kubaj12.online_store.identityaccess.domain.PasswordPolicy;

@Service
public class InvitationService {
    /** Transient delivery handoff. Never persist or log this value. */
    public record IssuedInvitation(UUID id, InvitationToken token) {}
    private final AccountLinkMail mail;
    private final AuditEventRecorder audit;
    private final InvitationStore store;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final TransactionTemplate transactions;
    public InvitationService(InvitationStore store, PasswordEncoder encoder, Clock clock, PlatformTransactionManager manager, AccountLinkMail mail, AuditEventRecorder audit) {
        this.audit = audit; this.mail = mail; this.store = store; this.encoder = encoder; this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }
    public void inviteEmployee(String email, UUID actor) { issue(email, InvitationRole.EMPLOYEE, actor); }
    public IssuedInvitation issue(String email, InvitationRole role, UUID actor) {
        String normalized = NormalizedEmail.of(email).value();
        return transactions.execute(status -> {
            store.lockEmail(normalized);
            store.authorizeActor(actor, role);
            var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            store.expirePending(normalized, now);
            if (store.accountExists(normalized) || store.pendingExists(normalized)) throw new InvitationException();
            return insert(normalized, role, actor, now);
        });
    }
    /** Resend preserves the stored role and serializes with acceptance, including old-token acceptance. */
    public IssuedInvitation resend(UUID invitationId, UUID actor) {
        return transactions.execute(status -> {
            var original = store.findById(invitationId).orElseThrow(InvitationException::new);
            store.lockEmail(original.email());
            var invitation = store.findById(invitationId).orElseThrow(InvitationException::new);
            store.authorizeActor(actor, invitation.role());
            if (!(invitation.status().equals("PENDING") || invitation.status().equals("EXPIRED"))
                    || store.accountExists(invitation.email())) throw new InvitationException();
            var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if (invitation.status().equals("PENDING")) {
                store.revoke(invitation.id(), now);
                audit.record(IdentityAudit.REVOKED.event(invitation.id(), new AuditActor(actor)));
            }
            // An expired historical invitation must never replace a newer pending invitation.
            if (store.pendingExists(invitation.email())) throw new InvitationException();
            return insert(invitation.email(), invitation.role(), actor, now);
        });
    }
    private IssuedInvitation insert(String email, InvitationRole role, UUID actor, java.time.Instant now) {
        var token = InvitationToken.generate();
        UUID id = UUID.randomUUID();
        store.insert(id, email, role, token.hash(), actor, now, now.plus(Duration.ofDays(7)));
        audit.record(IdentityAudit.INVITED.event(id, new AuditActor(actor)));
        mail.activationAfterCommit(email, token.value());
        return new IssuedInvitation(id, token);
    }
    public UUID accept(String rawToken, String password) {
        PasswordPolicy.validate(password);
        InvitationToken token;
        try { token = InvitationToken.parse(rawToken); }
        catch (IllegalArgumentException exception) { throw new InvitationException(); }
        UUID result = transactions.execute(status -> {
            var initial = store.findByHash(token.hash()).orElse(null);
            if (initial == null) return null;
            store.lockEmail(initial.email());
            var invitation = store.findByHash(token.hash()).orElseThrow(InvitationException::new);
            var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            store.expirePending(invitation.email(), now);
            if (!invitation.status().equals("PENDING") || !now.isBefore(invitation.expiresAt())
                    || store.accountExists(invitation.email())) return null;
            // Only an eligible invitation may incur BCrypt work. Keep the email lock
            // through hashing so another acceptance/resend cannot invalidate this state.
            String hash = encoder.encode(password);
            now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if (!now.isBefore(invitation.expiresAt())) {
                store.expirePending(invitation.email(), now);
                return null;
            }
            UUID accountId = UUID.randomUUID();
            if (!store.createAccount(accountId, invitation.email(), invitation.role(), hash, now)) return null;
            store.accept(invitation.id(), accountId, now);
            audit.record(IdentityAudit.ACCEPTED.event(invitation.id(), new AuditActor(accountId)));
            return accountId;
        });
        // Reject outside the transaction so an EXPIRED transition is committed.
        if (result == null) throw new InvitationException();
        return result;
    }
}
