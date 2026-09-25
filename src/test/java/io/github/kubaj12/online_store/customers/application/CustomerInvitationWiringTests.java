package io.github.kubaj12.online_store.customers.application;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import io.github.kubaj12.online_store.identityaccess.application.*;
import io.github.kubaj12.online_store.notifications.application.AccountLinkMail;
import io.github.kubaj12.online_store.shared.auditing.AuditEventRecorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
}
