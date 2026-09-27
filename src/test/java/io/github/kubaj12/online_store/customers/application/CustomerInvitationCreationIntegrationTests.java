package io.github.kubaj12.online_store.customers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.kubaj12.online_store.identityaccess.application.InvitationException;
import io.github.kubaj12.online_store.identityaccess.application.InvitationService;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

class CustomerInvitationCreationIntegrationTests extends PostgreSqlServiceTestSupport {

    private static final String EMAIL = "buyer@example.test";
    private static final String NIP = "5260250995";

    @Autowired CustomerAdministrationService customers;
    @Autowired InvitationService invitations;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsOnePendingInvitationWithTheCompletePayloadAndSendsTheIdentityActivationLink() {
        UUID employee = employee();
        CustomerProfileData profile = profile(NIP);

        UUID invitationId = customers.invite("  BUYER@EXAMPLE.TEST ", profile, employee);

        assertThat(jdbc.queryForMap("""
                SELECT i.email, i.role, i.status, d.company_name, d.nip, d.billing_street,
                       d.billing_building_number, d.billing_unit_number, d.billing_postal_code,
                       d.billing_city, d.billing_country, d.phone
                FROM identity_invitation i
                JOIN customer_invitation_data d ON d.invitation_id = i.id
                WHERE i.id = ?
                """, invitationId)).containsAllEntriesOf(java.util.Map.ofEntries(
                        java.util.Map.entry("email", EMAIL),
                        java.util.Map.entry("role", "CUSTOMER"),
                        java.util.Map.entry("status", "PENDING"),
                        java.util.Map.entry("company_name", profile.companyName()),
                        java.util.Map.entry("nip", NIP),
                        java.util.Map.entry("billing_street", profile.billingAddress().street()),
                        java.util.Map.entry("billing_building_number", profile.billingAddress().buildingNumber()),
                        java.util.Map.entry("billing_unit_number", profile.billingAddress().unitNumber()),
                        java.util.Map.entry("billing_postal_code", profile.billingAddress().postalCode().value()),
                        java.util.Map.entry("billing_city", profile.billingAddress().city()),
                        java.util.Map.entry("billing_country", "PL"),
                        java.util.Map.entry("phone", profile.phone().value())));

        assertThat(mailDelivery().delivered()).singleElement().satisfies(mail -> {
            assertThat(mail.recipient()).isEqualTo(EMAIL);
            assertThat(mail.subject()).isEqualTo("Aktywacja konta");
            assertThat(mail.plainTextBody()).contains("/invitations/accept/");
        });
    }

    @Test
    void resendTransfersTheCompletePayloadAndRecordsCustomerInvitationEvents() {
        UUID employee = employee();
        CustomerProfileData profile = profile(NIP);
        UUID original = customers.invite(EMAIL, profile, employee);

        var replacement = invitations.resend(original, employee);

        assertThat(jdbc.queryForObject("SELECT status FROM identity_invitation WHERE id = ?", String.class, original))
                .isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_invitation_data WHERE invitation_id = ?", Integer.class, original))
                .isZero();
        assertThat(jdbc.queryForMap("""
                SELECT company_name, nip, billing_street, billing_building_number, billing_unit_number,
                       billing_postal_code, billing_city, billing_country, phone
                FROM customer_invitation_data WHERE invitation_id = ?
                """, replacement.id())).containsAllEntriesOf(java.util.Map.of(
                        "company_name", profile.companyName(),
                        "nip", NIP,
                        "billing_street", profile.billingAddress().street(),
                        "billing_building_number", profile.billingAddress().buildingNumber(),
                        "billing_unit_number", profile.billingAddress().unitNumber(),
                        "billing_postal_code", profile.billingAddress().postalCode().value(),
                        "billing_city", profile.billingAddress().city(),
                        "billing_country", "PL",
                        "phone", profile.phone().value()));
        assertThat(jdbc.queryForList("""
                SELECT event_type FROM audit_event WHERE target_id IN (?, ?) ORDER BY occurred_at, id
                """, String.class, original.toString(), replacement.id().toString()))
                .containsExactlyInAnyOrder("identity.invitation.issued", "identity.invitation.revoked", "identity.invitation.issued");

        UUID account = invitations.accept(replacement.token().value(), "CorrectHorseBattery12");
        assertThat(jdbc.queryForObject("SELECT nip FROM customer_profile WHERE user_id = ?", String.class, account))
                .isEqualTo(NIP);
    }

    @Test
    void duplicateNormalizedEmailIsRejectedWithoutReplacingPayloadOrSendingAnotherLink() {
        UUID employee = employee();
        UUID original = customers.invite(EMAIL, profile(NIP), employee);

        assertThatThrownBy(() -> customers.invite(" BUYER@EXAMPLE.TEST ", profile("1234563218"), employee))
                .isInstanceOf(CustomerInvitationConflictException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM identity_invitation WHERE email = ? AND status = 'PENDING'",
                Integer.class, EMAIL)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT nip FROM customer_invitation_data WHERE invitation_id = ?",
                String.class, original)).isEqualTo(NIP);
        assertThat(mailDelivery().delivered()).hasSize(1);
    }

    @Test
    void conflictingNipRollsBackTheInvitationAndDoesNotSendAnActivationLink() {
        UUID employee = employee();
        customers.invite("first@example.test", profile(NIP), employee);

        assertThatThrownBy(() -> customers.invite("second@example.test", profile(NIP), employee))
                .isInstanceOf(CustomerInvitationConflictException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM identity_invitation WHERE email = 'second@example.test'",
                Integer.class)).isZero();
        assertThat(mailDelivery().delivered()).hasSize(1);
    }

    @Test
    void expiredInvitationRetainsItsPayloadButNoLongerReservesTheNip() {
        UUID employee = employee();
        UUID expired = customers.invite("expired@example.test", profile(NIP), employee);
        testClock().advance(Duration.ofDays(7));

        UUID replacement = customers.invite("replacement@example.test", profile(NIP), employee);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM customer_invitation_data WHERE invitation_id IN (?, ?)",
                Integer.class, expired, replacement)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM identity_invitation WHERE id = ?",
                String.class, replacement)).isEqualTo("PENDING");
        assertThat(mailDelivery().delivered()).hasSize(2);
    }

    @Test
    void cannotResendAnExpiredInvitationWhenAnotherPendingInvitationClaimsItsNip() {
        UUID employee = employee();
        UUID expired = customers.invite("expired@example.test", profile(NIP), employee);
        testClock().advance(Duration.ofDays(7));
        customers.invite("replacement@example.test", profile(NIP), employee);

        assertThatThrownBy(() -> invitations.resend(expired, employee))
                .isInstanceOf(InvitationException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM identity_invitation WHERE email = 'expired@example.test'",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM customer_invitation_data WHERE nip = ?",
                Integer.class, NIP)).isEqualTo(2);
        assertThat(mailDelivery().delivered()).hasSize(2);
    }

    @Test
    void cannotResendAnExpiredInvitationWhenAProfileHasClaimedItsNip() {
        UUID employee = employee();
        UUID expired = customers.invite("expired@example.test", profile(NIP), employee);
        testClock().advance(Duration.ofDays(7));
        customers.invite("replacement@example.test", profile(NIP), employee);
        invitations.accept(tokenFromDeliveredMail(1), "CorrectHorseBattery12");

        assertThatThrownBy(() -> invitations.resend(expired, employee))
                .isInstanceOf(InvitationException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM customer_profile WHERE nip = ?",
                Integer.class, NIP)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM identity_invitation WHERE email = 'expired@example.test'",
                Integer.class)).isOne();
        assertThat(mailDelivery().delivered()).hasSize(2);
    }

    @Test
    void missingProfileIsRejectedBeforeAnInvitationCanBeIssued() {
        UUID employee = employee();

        assertThatThrownBy(() -> customers.invite(EMAIL, null, employee))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete customer profile");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_invitation", Integer.class)).isZero();
        assertThat(mailDelivery().attempts()).isEmpty();
    }

    private UUID employee() {
        UUID id = UUID.randomUUID();
        Instant now = testClock().instant();
        new IdentityDatabaseFixture(jdbc).insertActiveUser(id, "employee-" + id + "@example.test", "EMPLOYEE", now);
        return id;
    }

    private static CustomerProfileData profile(String nip) {
        return CustomerProfileData.of("Przykładowa Sp. z o.o.", nip, "Prosta", "1", "2",
                "00-001", "Warszawa", "PL", "+48 600 700 800");
    }

    private String tokenFromDeliveredMail(int index) {
        String body = mailDelivery().delivered().get(index).plainTextBody();
        String link = body.split("\\n\\n")[1];
        return link.substring(link.lastIndexOf('/') + 1);
    }
}
