package io.github.kubaj12.online_store.cartordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.kubaj12.online_store.cartordering.web.CheckoutForm;
import io.github.kubaj12.online_store.cartordering.web.CustomerCheckoutController;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;
import io.github.kubaj12.online_store.testsupport.SecurityTestUsers;
import io.github.kubaj12.online_store.shared.validation.PolishAddress;
import io.github.kubaj12.online_store.shared.validation.PolishPhoneNumber;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CheckoutReviewLifecycleIntegrationTests extends PostgreSqlServiceTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired CartService carts;
    @Autowired CheckoutReviewService reviews;
    @Autowired CheckoutReviewStore store;
    UUID customer;
    UUID sku;

    @BeforeEach void setUpCart() {
        customer = insertCustomer("buyer@example.test", "1111111111");
        authenticate(customer, "buyer@example.test");
        UUID product = UUID.randomUUID();
        sku = UUID.randomUUID();
        var now = OffsetDateTime.ofInstant(testClock().instant(), ZoneOffset.UTC);
        jdbc.update("INSERT INTO catalog_product(id,name,category,is_active,created_at,updated_at) VALUES(?,?,?,TRUE,?,?)",
                product, "Produkt", "Test", now, now);
        jdbc.update("""
                INSERT INTO catalog_sku(id,product_id,code,base_net_price,vat_rate,available_quantity,is_active,created_at,updated_at)
                VALUES(?,?,?,10.00,23.00,10,TRUE,?,?)
                """, sku, product, "CHECKOUT-SKU", now, now);
        carts.add(sku, 2);
    }

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @ValueSource(strings = {"add", "update", "remove"})
    void cartMutationsImmediatelyInvalidateReviewsEvenWhenQuantityReturnsToOriginal(String mutation) {
        var first = reviews.issue(details(), null);
        UUID cart = carts.activeCart().id();
        switch (mutation) {
            case "add" -> { carts.add(sku, 1); carts.update(sku, 2); }
            case "update" -> { carts.update(sku, 3); carts.update(sku, 2); }
            case "remove" -> { carts.remove(sku); carts.add(sku, 2); }
            default -> throw new AssertionError();
        }
        assertThat(store.activeForCart(cart, customer)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_ordering_checkout_review WHERE cart_id=? AND invalidated_at IS NOT NULL",
                Integer.class, cart)).isEqualTo(1);
        assertThat(reviews.refresh(first.token()).reviewChanged()).isTrue();
        assertThat(reviews.refresh(first.token()).reviewValid()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"price", "vat", "stock", "inactive"})
    void refreshShowsCurrentDataAndInvalidatesChangedCommercialReviewsWithoutIssuing(String change) {
        var first = reviews.issue(details(), null);
        switch (change) {
            case "price" -> jdbc.update("UPDATE catalog_sku SET base_net_price=15.00 WHERE id=?", sku);
            case "vat" -> jdbc.update("UPDATE catalog_sku SET vat_rate=8.00 WHERE id=?", sku);
            case "stock" -> jdbc.update("UPDATE catalog_sku SET available_quantity=1 WHERE id=?", sku);
            case "inactive" -> jdbc.update("UPDATE catalog_sku SET is_active=FALSE WHERE id=?", sku);
            default -> throw new AssertionError();
        }
        var state = reviews.refresh(first.token());
        assertThat(state.reviewChanged()).isTrue();
        assertThat(state.reviewValid()).isFalse();
        assertThat(store.activeForCart(state.cartId(), customer)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_ordering_checkout_review", Integer.class)).isEqualTo(1);
        if (change.equals("price")) assertThat(state.cart().totals().gross()).isEqualByComparingTo("36.90");
        if (change.equals("vat")) assertThat(state.cart().totals().gross()).isEqualByComparingTo("21.60");
    }

    @Test void unchangedReviewSurvivesDisplayButExpiryAndSupersessionRequireReviewAgain() {
        var first = reviews.issue(details(), null);
        assertThat(reviews.refresh(first.token()).reviewValid()).isTrue();
        assertThat(reviews.refresh(first.token()).reviewChanged()).isFalse();
        // Stock changes that still cover the requested quantity do not alter the reviewed amounts.
        jdbc.update("UPDATE catalog_sku SET available_quantity=8 WHERE id=?", sku);
        assertThat(reviews.refresh(first.token()).reviewValid()).isTrue();
        var second = reviews.issue(details(), null);
        assertThat(reviews.refresh(first.token()).reviewValid()).isFalse();
        assertThat(reviews.refresh(second.token()).reviewValid()).isTrue();
        testClock().advance(Duration.ofMinutes(30));
        assertThat(reviews.refresh(second.token()).reviewChanged()).isTrue();
        assertThat(store.activeForCart(carts.activeCart().id(), customer)).isEmpty();
    }

    @Test void foreignOrForgedTokensCannotValidateOrInvalidateAnotherCustomersReview() {
        var first = reviews.issue(details(), null);
        UUID cart = carts.activeCart().id();
        UUID other = insertCustomer("other@example.test", "2222222222");
        authenticate(other, "other@example.test");
        carts.add(sku, 1);
        assertThat(reviews.refresh(first.token()).reviewValid()).isFalse();
        assertThat(store.activeForCart(cart, other)).isEmpty();
        authenticate(customer, "buyer@example.test");
        assertThat(reviews.refresh("untrusted-token").reviewValid()).isFalse();
        assertThat(reviews.refresh(first.token()).reviewValid()).isTrue();
    }

    @Test void changedReviewIsDetectedEvenWithoutAPresentedToken() {
        reviews.issue(details(), null);
        jdbc.update("UPDATE catalog_sku SET vat_rate=8.00 WHERE id=?", sku);
        assertThat(reviews.refresh(null).reviewChanged()).isTrue();
        assertThat(store.activeForCart(carts.activeCart().id(), customer)).isEmpty();
        var second = reviews.issue(details(), null);
        assertThat(reviews.refresh(second.token()).reviewValid()).isTrue();
    }

    @Test void reviewTracksEffectiveCustomerPricesRatherThanUnusedBasePrices() {
        var now = OffsetDateTime.ofInstant(testClock().instant(), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO catalog_customer_specific_price(customer_id,sku_id,net_price,currency,created_at,updated_at)
                VALUES(?,?,7.00,'PLN',?,?)
                """, customer, sku, now, now);
        var first = reviews.issue(details(), null);
        jdbc.update("UPDATE catalog_sku SET base_net_price=20.00 WHERE id=?", sku);
        assertThat(reviews.refresh(first.token()).reviewValid()).isTrue();
        jdbc.update("UPDATE catalog_customer_specific_price SET net_price=8.00 WHERE customer_id=? AND sku_id=?", customer, sku);
        assertThat(reviews.refresh(first.token()).reviewChanged()).isTrue();
        var second = reviews.issue(details(), null);
        jdbc.update("DELETE FROM catalog_customer_specific_price WHERE customer_id=? AND sku_id=?", customer, sku);
        assertThat(reviews.refresh(second.token()).reviewChanged()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"price", "vat", "cart", "cart-restored"})
    void committedChangesBetweenControllerRefreshAndIssueRequireAnotherExplicitPost(String change) {
        var first = reviews.issue(details(), null);
        // Only the call boundary is wrapped: reads, mutations and issuance use real Spring transactions
        // and PostgreSQL. The mutation commits after refresh releases its lock, before issue starts.
        var calls = mock(CheckoutReviewService.class);
        var changeAfterFirstRefresh = new AtomicBoolean(true);
        when(calls.refresh(nullable(String.class))).thenAnswer(invocation -> {
            var state = reviews.refresh(invocation.getArgument(0));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (changeAfterFirstRefresh.getAndSet(false)) {
                assertThat(state.reviewValid()).isTrue();
                assertThat(state.reviewChanged()).isFalse();
                switch (change) {
                    case "price" -> jdbc.update("UPDATE catalog_sku SET base_net_price=15.00 WHERE id=?", sku);
                    case "vat" -> jdbc.update("UPDATE catalog_sku SET vat_rate=8.00 WHERE id=?", sku);
                    case "cart" -> carts.update(sku, 3);
                    case "cart-restored" -> { carts.update(sku, 3); carts.update(sku, 2); }
                    default -> throw new AssertionError();
                }
            }
            return state;
        });
        when(calls.issue(any(), nullable(String.class))).thenAnswer(invocation ->
                reviews.issue(invocation.getArgument(0), invocation.getArgument(1)));
        var controller = new CustomerCheckoutController(calls);
        var form = checkoutForm(first.token());
        var request = new MockHttpServletRequest("POST", "/cart/checkout");
        request.setRemoteUser("buyer@example.test");

        var changedResponse = controller.review(form, new BeanPropertyBindingResult(form, "form"),
                request, new MockHttpServletResponse(), new ExtendedModelMap());

        assertThat(changedResponse.getModel()).containsEntry("reviewChanged", true).containsEntry("reviewIssued", false);
        assertThat(form.getCheckoutToken()).isNull();
        assertThat(form.getPurchaseOrderNumber()).isEqualTo("PO-17");
        assertThat(form.getStreet()).isEqualTo("Prosta");
        var current = (CartService.CartView) changedResponse.getModel().get("cart");
        String expectedGross = switch (change) {
            case "price", "cart" -> "36.90";
            case "vat" -> "21.60";
            default -> "24.60";
        };
        assertThat(current.totals().gross()).isEqualByComparingTo(expectedGross);
        assertThat(store.activeForCart(carts.activeCart().id(), customer)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_ordering_checkout_review", Integer.class)).isEqualTo(1);
        assertThat(reviews.refresh(first.token()).reviewValid()).isFalse();

        // The refreshed form contains no token; a subsequent explicit POST may now issue its review.
        var reviewedResponse = controller.review(form, new BeanPropertyBindingResult(form, "form"),
                request, new MockHttpServletResponse(), new ExtendedModelMap());
        assertThat(reviewedResponse.getModel()).containsEntry("reviewChanged", false).containsEntry("reviewIssued", true);
        assertThat(reviews.refresh(form.getCheckoutToken()).reviewValid()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_ordering_checkout_review", Integer.class)).isEqualTo(2);
    }

    @Test void issuanceCanReplaceAnUnchangedPriorReview() {
        var first = reviews.issue(details(), null);
        var second = reviews.issue(details(), first.token());
        assertThat(second.issued()).isTrue();
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(reviews.refresh(first.token()).reviewValid()).isFalse();
        assertThat(reviews.refresh(second.token()).reviewValid()).isTrue();
    }

    private static CheckoutForm checkoutForm(String token) {
        var form = new CheckoutForm();
        form.setPurchaseOrderNumber("PO-17");
        form.setContactName("Jan Kowalski");
        form.setContactPhone("600700800");
        form.setStreet("Prosta");
        form.setBuildingNumber("1");
        form.setPostalCode("00-001");
        form.setCity("Warszawa");
        form.setCheckoutToken(token);
        return form;
    }

    private UUID insertCustomer(String email, String nip) {
        UUID id = UUID.randomUUID();
        var now = OffsetDateTime.ofInstant(testClock().instant(), ZoneOffset.UTC);
        new IdentityDatabaseFixture(jdbc).insertActiveUser(id, email, "CUSTOMER", testClock().instant());
        jdbc.update("""
                INSERT INTO customer_profile(user_id,company_name,nip,billing_street,billing_building_number,
                    billing_postal_code,billing_city,created_at,updated_at)
                VALUES(?,'Company',?,'Prosta','1','00-001','Warszawa',?,?)
                """, id, nip, now, now);
        return id;
    }

    private static void authenticate(UUID customer, String email) {
        var principal = SecurityTestUsers.principal(customer, email, "CUSTOMER");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
    }

    private static CheckoutDetails details() {
        return new CheckoutDetails("PO-17", "Jan Kowalski", PolishPhoneNumber.of("600700800"),
                PolishAddress.of("Prosta", "1", null, "00-001", "Warszawa", "PL"));
    }
}
