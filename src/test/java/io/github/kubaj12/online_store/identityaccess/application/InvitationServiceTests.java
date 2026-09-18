package io.github.kubaj12.online_store.identityaccess.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import io.github.kubaj12.online_store.identityaccess.domain.InvitationRole;
import io.github.kubaj12.online_store.identityaccess.domain.InvitationToken;
import io.github.kubaj12.online_store.testsupport.TestClock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InvitationServiceTests {
    private static final String EMAIL = "invited@example.test";
    private static final String PASSWORD = "CorrectHorseBattery12";
    private final InvitationStore store = mock(InvitationStore.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final TestClock clock = new TestClock();
    private final InvitationToken token = InvitationToken.generate();
    private final InvitationService service = new InvitationService(store, encoder, clock, manager, org.mockito.Mockito.mock(io.github.kubaj12.online_store.notifications.application.AccountLinkMail.class), org.mockito.Mockito.mock(io.github.kubaj12.online_store.shared.auditing.AuditEventRecorder.class));

    InvitationServiceTests() {
        when(manager.getTransaction(any())).thenReturn(transaction);
    }

    @Test void unknownCanonicalTokenDoesNotHashPassword() {
        when(store.findByHash(any(byte[].class))).thenReturn(Optional.empty());
        rejectWithoutHashing();
        verify(store, never()).lockEmail(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "REVOKED", "EXPIRED"})
    void terminalStatesDoNotHashPassword(String status) {
        stub(invitation(status, clock.instant().plus(Duration.ofDays(7))));
        rejectWithoutHashing();
    }

    @Test void elapsedPendingTokenCommitsExpiryWithoutHashing() {
        stub(invitation("PENDING", clock.instant()));
        rejectWithoutHashing();
        verify(store).expirePending(EMAIL, clock.instant());
        verify(manager).commit(transaction);
        verify(manager, never()).rollback(any());
    }

    @Test void lockedReReadRejectsRevokedInvitationWithoutHashing() {
        var pending = invitation("PENDING", clock.instant().plus(Duration.ofDays(7)));
        var revoked = new InvitationStore.Invitation(pending.id(), EMAIL, pending.role(), "REVOKED", pending.expiresAt());
        when(store.findByHash(any(byte[].class))).thenReturn(Optional.of(pending), Optional.of(revoked));
        rejectWithoutHashing();
        verify(store).lockEmail(EMAIL);
    }

    @Test void existingAccountDoesNotHashPassword() {
        stub(invitation("PENDING", clock.instant().plus(Duration.ofDays(7))));
        when(store.accountExists(EMAIL)).thenReturn(true);
        rejectWithoutHashing();
    }

    @Test void expiryDuringHashingCommitsExpiryWithoutCreatingAccount() {
        Instant expiresAt = clock.instant().plusSeconds(1);
        stub(invitation("PENDING", expiresAt));
        when(encoder.encode(PASSWORD)).thenAnswer(invocation -> {
            clock.set(expiresAt);
            return "synthetic-hash";
        });
        assertThatThrownBy(() -> service.accept(token.value(), PASSWORD)).isInstanceOf(InvitationException.class);
        verify(encoder).encode(PASSWORD);
        verify(store).expirePending(EMAIL, expiresAt);
        verify(store, never()).createAccount(any(), anyString(), any(), anyString(), any());
        verify(store, never()).accept(any(), any(), any());
        verify(manager).commit(transaction);
        verify(manager, never()).rollback(any());
    }

    @Test void validAcceptanceHashesAfterLockedChecksAndUsesCurrentClock() {
        var invitation = invitation("PENDING", clock.instant().plus(Duration.ofDays(7)));
        stub(invitation);
        when(encoder.encode(PASSWORD)).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(1));
            return "synthetic-hash";
        });
        when(store.createAccount(any(), eq(EMAIL), eq(invitation.role()), eq("synthetic-hash"), any())).thenReturn(true);
        UUID account = service.accept(token.value(), PASSWORD);
        var order = inOrder(store, encoder);
        order.verify(store).findByHash(any(byte[].class));
        order.verify(store).lockEmail(EMAIL);
        order.verify(store).findByHash(any(byte[].class));
        order.verify(store).expirePending(eq(EMAIL), any());
        order.verify(store).accountExists(EMAIL);
        order.verify(encoder).encode(PASSWORD);
        order.verify(store).createAccount(account, EMAIL, invitation.role(), "synthetic-hash", clock.instant());
        order.verify(store).accept(invitation.id(), account, clock.instant());
    }

    private InvitationStore.Invitation invitation(String status, Instant expiresAt) {
        return new InvitationStore.Invitation(UUID.randomUUID(), EMAIL, InvitationRole.EMPLOYEE, status, expiresAt);
    }
    private void stub(InvitationStore.Invitation invitation) {
        when(store.findByHash(any(byte[].class))).thenReturn(Optional.of(invitation));
    }
    private void rejectWithoutHashing() {
        assertThatThrownBy(() -> service.accept(token.value(), PASSWORD)).isInstanceOf(InvitationException.class);
        verifyNoInteractions(encoder);
        verify(store, never()).createAccount(any(), anyString(), any(), anyString(), any());
        verify(store, never()).accept(any(), any(), any());
    }
}
