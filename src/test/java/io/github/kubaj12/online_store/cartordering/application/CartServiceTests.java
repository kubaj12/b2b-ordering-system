package io.github.kubaj12.online_store.cartordering.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;

class CartServiceTests {
    private final CartStore store = mock(CartStore.class);
    private final CartService service = new CartService(store);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usesOnlyTheSignedInCustomerForBothEntryPoints() {
        UUID customerId = UUID.randomUUID();
        authenticate(customerId, "CUSTOMER", "ACTIVE");

        service.activeCart();
        service.cartForEdit();

        verify(store, org.mockito.Mockito.times(2)).findOrCreateActive(customerId);
    }

    @Test
    void deniesAnonymousStaffAndBlockedAccountsBeforeTouchingTheStore() {
        assertThatThrownBy(service::activeCart).isInstanceOf(AccessDeniedException.class);

        authenticate(UUID.randomUUID(), "EMPLOYEE", "ACTIVE");
        assertThatThrownBy(service::cartForEdit).isInstanceOf(AccessDeniedException.class);

        authenticate(UUID.randomUUID(), "CUSTOMER", "BLOCKED");
        assertThatThrownBy(service::activeCart).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(store);
    }

    private static void authenticate(UUID accountId, String role, String status) {
        var principal = new AccountPrincipal(accountId, "account@example.test", "hash", role, status, 0);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }
}
