package io.github.kubaj12.online_store.cartordering.application;

import java.util.UUID;
import io.github.kubaj12.online_store.identityaccess.application.AccountPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer-scoped access to the persistent active cart. */
@Service
public class CartService {
    private final CartStore store;

    public CartService(CartStore store) {
        this.store = store;
    }

    /**
     * Lazily creates a cart for a customer and reuses that same database row on
     * subsequent requests, including requests from a different login session.
     */
    @Transactional
    public CartStore.Cart activeCart() {
        return store.findOrCreateActive(authenticatedCustomerId());
    }

    /**
     * Used by edit flows: after checkout completes the previous cart, the first
     * subsequent edit obtains a new active cart through the same lazy operation.
     */
    @Transactional
    public CartStore.Cart cartForEdit() {
        return activeCart();
    }

    private static UUID authenticatedCustomerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountPrincipal principal)
                || !"CUSTOMER".equals(principal.role()) || !principal.isEnabled()
                || principal.accountId() == null) {
            throw new AccessDeniedException("An active authenticated customer is required to access a cart");
        }
        return principal.accountId();
    }
}
