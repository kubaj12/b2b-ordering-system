package io.github.kubaj12.online_store.customers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import io.github.kubaj12.online_store.identityaccess.application.*;
import io.github.kubaj12.online_store.notifications.application.AccountLinkMail;
import io.github.kubaj12.online_store.shared.auditing.AuditEventRecorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CustomerInvitationWiringTests {
    @Test
    void productionServicesAndCustomerLifecycleHandlerHaveNoConstructorCycle() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(CustomerAdministrationStore.class, () -> mock(CustomerAdministrationStore.class));
            context.registerBean(InvitationStore.class, () -> mock(InvitationStore.class));
            context.registerBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class));
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.registerBean(AccountLinkMail.class, () -> mock(AccountLinkMail.class));
            context.registerBean(AuditEventRecorder.class, () -> mock(AuditEventRecorder.class));
            context.registerBean(AccountStatusService.class, () -> mock(AccountStatusService.class));
            context.register(InvitationService.class, CustomerAdministrationService.class,
                    CustomerInvitationLifecycleHandler.class);

            context.refresh();

            assertThat(context.getBean(InvitationService.class)).isNotNull();
            assertThat(context.getBean(CustomerAdministrationService.class)).isNotNull();
            assertThat(context.getBean(InvitationLifecycleHandler.class))
                    .isInstanceOf(CustomerInvitationLifecycleHandler.class);
        }
    }

    @Test
    void customerResendChecksTheCurrentNipClaimBeforeTransferringItsPayload() {
        CustomerAdministrationStore store = mock(CustomerAdministrationStore.class);
        var handler = new CustomerInvitationLifecycleHandler(store);
        UUID oldId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T12:00:00Z");

        handler.onResend(oldId, newId, "CUSTOMER", now);

        var order = inOrder(store);
        order.verify(store).requireInvitationNipAvailable(oldId, now);
        order.verify(store).transferInvitationPayload(oldId, newId, now);
    }

    @Test
    void customerResendReportsANipClaimAsAnUnavailableInvitationWithoutTransferringPayload() {
        CustomerAdministrationStore store = mock(CustomerAdministrationStore.class);
        var handler = new CustomerInvitationLifecycleHandler(store);
        UUID oldId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        doThrow(new DuplicateKeyException("claimed"))
                .when(store).requireInvitationNipAvailable(oldId, now);

        assertThatThrownBy(() -> handler.onResend(oldId, UUID.randomUUID(), "CUSTOMER", now))
                .isInstanceOf(InvitationException.class);

        verify(store, never()).transferInvitationPayload(any(), any(), any());
    }
}
