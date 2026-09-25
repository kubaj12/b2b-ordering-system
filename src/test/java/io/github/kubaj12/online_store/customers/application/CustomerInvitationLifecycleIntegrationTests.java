package io.github.kubaj12.online_store.customers.application;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.kubaj12.online_store.identityaccess.application.InvitationService;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

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
            store.requireNipAvailable(RELEASED_NIP, null);
            store.addInvitationPayload(invitationId, profile(RELEASED_NIP), now);
        });
        UUID customer = invitations.accept(first.token().value(), PASSWORD);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_invitation_data WHERE invitation_id = ?",
                Integer.class, first.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM identity_invitation WHERE id = ?",
                String.class, first.id())).isEqualTo("ACCEPTED");

        customers.update(customer, profile(REPLACEMENT_NIP));
        var second = invitations.inviteCustomer("second@example.test", employee, invitationId -> {
            store.requireNipAvailable(RELEASED_NIP, null);
            store.addInvitationPayload(invitationId, profile(RELEASED_NIP), testClock().instant());
        });

        assertThat(jdbc.queryForObject("SELECT nip FROM customer_invitation_data WHERE invitation_id = ?",
                String.class, second.id())).isEqualTo(RELEASED_NIP);
        assertThat(jdbc.queryForObject("SELECT nip FROM customer_profile WHERE user_id = ?",
                String.class, customer)).isEqualTo(REPLACEMENT_NIP);
    }

    private static CustomerProfileData profile(String nip) {
        return CustomerProfileData.of("Przykładowa Sp. z o.o.", nip, "Prosta", "1", null,
                "00-001", "Warszawa", "PL", null);
    }
}
