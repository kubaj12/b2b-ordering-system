package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.identityaccess.domain.PasswordPolicy;

@Service
public class PasswordChangeService {
    private final PasswordChangeStore store;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public PasswordChangeService(PasswordChangeStore store, PasswordEncoder encoder, Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.encoder = encoder;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    public boolean change(UUID accountId, long expectedSecurityVersion, String currentPassword, String newPassword) {
        if (accountId == null || currentPassword == null || currentPassword.isEmpty()) return false;
        PasswordPolicy.validate(newPassword);
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var account = store.lockActivePassword(accountId);
            if (account.isEmpty() || account.get().securityVersion() != expectedSecurityVersion
                    || !encoder.matches(currentPassword, account.get().passwordHash())) return false;
            store.replacePassword(accountId, encoder.encode(newPassword), clock.instant().truncatedTo(ChronoUnit.MICROS));
            return true;
        }));
    }
}
