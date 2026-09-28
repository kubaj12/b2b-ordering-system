package io.github.kubaj12.online_store.customers.application;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.kubaj12.online_store.identityaccess.application.InvitationService;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kubaj12.online_store.identityaccess.application.InvitationException;

class CustomerInvitationLifecycleIntegrationTests extends PostgreSqlServiceTestSupport {
    private static final String PASSWORD = "CorrectHorseBattery12";
    private static final String RELEASED_NIP = "5260250995";
    private static final String REPLACEMENT_NIP = "1234563218";

    @Autowired InvitationService invitations;
    @Autowired CustomerAdministrationService customers;
    @Autowired CustomerAdministrationStore store;
    @Autowired JdbcTemplate jdbc;

    @Test
    void activationConsumesPayloadAndReleasesNipForANewInvitationAfterProfileCorrection() {
        UUID employee = UUID.randomUUID();
        Instant now = testClock().instant();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(employee, "employee@example.test", "EMPLOYEE", now);

        var first = invitations.inviteCustomer("first@example.test", employee, invitationId -> {
            store.requireNipAvailable(RELEASED_NIP, null, now);
            store.addInvitationPayload(invitationId, profile(RELEASED_NIP), now);
        });
        UUID customer = invitations.accept(first.token().value(), PASSWORD);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_invitation_data WHERE invitation_id = ?",
                Integer.class, first.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM identity_invitation WHERE id = ?",
                String.class, first.id())).isEqualTo("ACCEPTED");

        CustomerProfileData edited = CustomerProfileData.of("Zmieniona firma", REPLACEMENT_NIP,
                "Nowa", "9B", "12", "30-001", "Kraków", "PL", "+48 500 600 700");
        customers.update(customer, edited);
        var second = invitations.inviteCustomer("second@example.test", employee, invitationId -> {
            store.requireNipAvailable(RELEASED_NIP, null, testClock().instant());
            store.addInvitationPayload(invitationId, profile(RELEASED_NIP), testClock().instant());
        });

        assertThat(jdbc.queryForObject("SELECT nip FROM customer_invitation_data WHERE invitation_id = ?",
                String.class, second.id())).isEqualTo(RELEASED_NIP);
        assertThat(jdbc.queryForObject("SELECT nip FROM customer_profile WHERE user_id = ?",
                String.class, customer)).isEqualTo(REPLACEMENT_NIP);
        assertThat(jdbc.queryForMap("""
                SELECT company_name, billing_street, billing_building_number, billing_unit_number,
                       billing_postal_code, billing_city, phone FROM customer_profile WHERE user_id = ?
                """,
                customer)).containsAllEntriesOf(java.util.Map.of(
                        "company_name", "Zmieniona firma", "billing_street", "Nowa",
                        "billing_building_number", "9B", "billing_unit_number", "12",
                        "billing_postal_code", "30-001", "billing_city", "Kraków", "phone", edited.phone().value()));
    }

    @Test
    void competingCustomerActivationsCreateExactlyOneAccountAndProfile() throws Exception {
        UUID employee = employee();
        var invitation = invitations.inviteCustomer("race@example.test", employee, id -> {
            store.requireNipAvailable(RELEASED_NIP, null, testClock().instant());
            store.addInvitationPayload(id, profile(RELEASED_NIP), testClock().instant());
        });
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> acceptAfterBarrier(invitation.token().value(), ready, start));
            var second = executor.submit(() -> acceptAfterBarrier(invitation.token().value(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var outcomes = java.util.List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(UUID.class::isInstance).count()).isOne();
            assertThat(outcomes.stream().filter(InvitationException.class::isInstance).count()).isOne();
        } finally {
            start.countDown();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_user WHERE email = 'race@example.test'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_profile WHERE nip = ?", Integer.class, RELEASED_NIP)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM identity_invitation WHERE id = ?", String.class, invitation.id())).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_invitation_data WHERE invitation_id = ?", Integer.class, invitation.id())).isZero();
    }

    private Object acceptAfterBarrier(String token, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race start timed out");
        try { return invitations.accept(token, PASSWORD); }
        catch (InvitationException exception) { return exception; }
    }

    @Test
    void activationCreatesOneActiveCustomerAndCopiesTheCompletePendingPayload() {
        UUID employee = employee();
        CustomerProfileData pending = CustomerProfileData.of("Zażółć Sp. z o.o.", RELEASED_NIP,
                "Długa", "12A", "7", "80-831", "Gdańsk", "PL", "+48 600 700 800");
        var invitation = invitations.inviteCustomer("  BUYER@EXAMPLE.TEST ", employee, id -> {
            store.requireNipAvailable(RELEASED_NIP, null, testClock().instant());
            store.addInvitationPayload(id, pending, testClock().instant());
        });

        UUID account = invitations.accept(invitation.token().value(), PASSWORD);

        assertThat(jdbc.queryForMap("""
                SELECT u.email, u.role, u.status, p.company_name, p.nip, p.billing_street,
                       p.billing_building_number, p.billing_unit_number, p.billing_postal_code,
                       p.billing_city, p.billing_country, p.phone
                FROM identity_user u JOIN customer_profile p ON p.user_id = u.id WHERE u.id = ?
                """, account)).containsAllEntriesOf(java.util.Map.ofEntries(
                        java.util.Map.entry("email", "buyer@example.test"),
                        java.util.Map.entry("role", "CUSTOMER"),
                        java.util.Map.entry("status", "ACTIVE"),
                        java.util.Map.entry("company_name", pending.companyName()),
                        java.util.Map.entry("nip", pending.nip().value()),
                        java.util.Map.entry("billing_street", pending.billingAddress().street()),
                        java.util.Map.entry("billing_building_number", pending.billingAddress().buildingNumber()),
                        java.util.Map.entry("billing_unit_number", pending.billingAddress().unitNumber()),
                        java.util.Map.entry("billing_postal_code", pending.billingAddress().postalCode().value()),
                        java.util.Map.entry("billing_city", pending.billingAddress().city()),
                        java.util.Map.entry("billing_country", "PL"),
                        java.util.Map.entry("phone", pending.phone().value())));
        assertThat(jdbc.queryForObject("SELECT status FROM identity_invitation WHERE id = ?",
                String.class, invitation.id())).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_invitation_data WHERE invitation_id = ?",
                Integer.class, invitation.id())).isZero();
    }

    @Test
    void conflictingNipRollsBackAccountAndProfileAndLeavesInvitationUsable() {
        UUID employee = employee();
        var invitation = invitations.inviteCustomer("buyer@example.test", employee, id -> {
            store.requireNipAvailable(RELEASED_NIP, null, testClock().instant());
            store.addInvitationPayload(id, profile(RELEASED_NIP), testClock().instant());
        });
        UUID existingCustomer = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(existingCustomer,
                "existing@example.test", "CUSTOMER", testClock().instant());
        insertProfile(existingCustomer, RELEASED_NIP);

        assertThatThrownBy(() -> invitations.accept(invitation.token().value(), PASSWORD))
                .isInstanceOf(InvitationException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM identity_user WHERE email = 'buyer@example.test'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_profile WHERE user_id <> ?",
                Integer.class, existingCustomer)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM identity_invitation WHERE id = ?",
                String.class, invitation.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_invitation_data WHERE invitation_id = ?",
                Integer.class, invitation.id())).isOne();
    }

    private UUID employee() {
        UUID employee = UUID.randomUUID();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(employee, "employee-" + employee + "@example.test",
                "EMPLOYEE", testClock().instant());
        return employee;
    }

    private void insertProfile(UUID accountId, String nip) {
        jdbc.update("""
            INSERT INTO customer_profile (user_id, company_name, nip, billing_street,
                billing_building_number, billing_postal_code, billing_city, billing_country,
                created_at, updated_at) VALUES (?, 'Existing Sp. z o.o.', ?, 'Prosta', '1',
                '00-001', 'Warszawa', 'PL', ?, ?)
            """, accountId, nip,
                testClock().instant().atOffset(java.time.ZoneOffset.UTC),
                testClock().instant().atOffset(java.time.ZoneOffset.UTC));
    }

    private static CustomerProfileData profile(String nip) {
        return CustomerProfileData.of("Przykładowa Sp. z o.o.", nip, "Prosta", "1", null,
                "00-001", "Warszawa", "PL", null);
    }
}
