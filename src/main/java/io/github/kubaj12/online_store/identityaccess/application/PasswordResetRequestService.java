package io.github.kubaj12.online_store.identityaccess.application;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.kubaj12.online_store.identityaccess.domain.*;
import io.github.kubaj12.online_store.notifications.application.AccountLinkMail;
@Service
public class PasswordResetRequestService {
    private final PasswordResetRequestStore store;
    private final AccountLinkMail mail;
    private final Clock clock;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder;
    private final TransactionTemplate transactions;
    public PasswordResetRequestService(PasswordResetRequestStore store, AccountLinkMail mail, Clock clock, PlatformTransactionManager manager, org.springframework.security.crypto.password.PasswordEncoder encoder) {
        this.encoder = encoder; this.store = store; this.mail = mail; this.clock = clock; this.transactions = new TransactionTemplate(manager);
    }
    public boolean reset(String rawToken, String password) {
        PasswordPolicy.validate(password);
        InvitationToken token;
        try { token = InvitationToken.parse(rawToken); }
        catch (IllegalArgumentException ignored) { return false; }
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var user = store.findUser(token.hash());
            if (user.isEmpty() || !store.lockActiveUser(user.get())) return false;
            // Read time only after any lock wait; reject historical/ineligible tokens before BCrypt.
            if (!store.eligible(user.get(), token.hash(), clock.instant().truncatedTo(ChronoUnit.MICROS))) return false;
            String passwordHash = encoder.encode(password);
            return store.replacePassword(user.get(), token.hash(), passwordHash, clock.instant().truncatedTo(ChronoUnit.MICROS));
        }));
    }
    /** Always returns the same result, including unknown, blocked, malformed and undeliverable addresses. */
    public void request(String email) {
        String normalized;
        try { normalized = NormalizedEmail.of(email).value(); }
        catch (IllegalArgumentException ignored) { return; }
        transactions.executeWithoutResult(status -> {
            var account = store.lockActiveAccount(normalized);
            if (account.isEmpty()) return;
            var token = InvitationToken.generate();
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            store.replace(account.get(), token.hash(), now, now.plus(Duration.ofHours(1)));
            mail.resetAfterCommit(normalized, token.value());
        });
    }
}
