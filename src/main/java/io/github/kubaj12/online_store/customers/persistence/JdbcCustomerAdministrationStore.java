package io.github.kubaj12.online_store.customers.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import io.github.kubaj12.online_store.customers.application.*;
import io.github.kubaj12.online_store.shared.validation.*;

@Repository
public class JdbcCustomerAdministrationStore implements CustomerAdministrationStore, CustomerBillingSnapshotProvider {
    private final JdbcTemplate jdbc;
    public JdbcCustomerAdministrationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Summary> customers() {
        return jdbc.query("""
            SELECT u.id, u.email, u.status, p.company_name, p.nip
            FROM identity_user u JOIN customer_profile p ON p.user_id = u.id
            WHERE u.role = 'CUSTOMER' ORDER BY p.company_name, u.email
            """, (rs, n) -> new Summary(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
    }
    public List<Pending> pendingInvitations() {
        return jdbc.query("""
            SELECT i.id, i.email, i.status, i.expires_at, d.*
            FROM identity_invitation i JOIN customer_invitation_data d ON d.invitation_id = i.id
            WHERE i.role = 'CUSTOMER' AND i.status IN ('PENDING','EXPIRED')
            ORDER BY i.created_at DESC
            """, (rs, n) -> new Pending(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("status"), profile(rs), instant(rs, "expires_at")));
    }
    public Optional<Detail> detail(UUID id) {
        return jdbc.query("""
            SELECT u.id, u.email, u.status, p.* FROM identity_user u
            JOIN customer_profile p ON p.user_id = u.id WHERE u.id = ? AND u.role = 'CUSTOMER'
            """, (rs, n) -> new Detail(rs.getObject("id", UUID.class), rs.getString("email"),
                    rs.getString("status"), profile(rs), instant(rs, "created_at"), instant(rs, "updated_at")), id)
            .stream().findFirst();
    }
    @Transactional
    public void requireNipAvailable(String nip, UUID editedAccountId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 941733))", rs -> { }, nip);
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
              SELECT 1 FROM customer_profile WHERE nip = ? AND (?::uuid IS NULL OR user_id <> ?::uuid)
              UNION ALL SELECT 1 FROM customer_invitation_data d JOIN identity_invitation i ON i.id=d.invitation_id
                WHERE d.nip = ? AND i.status IN ('PENDING','EXPIRED')
            )
            """, Boolean.class, nip, editedAccountId, editedAccountId, nip));
        if (exists) throw new org.springframework.dao.DuplicateKeyException("customer NIP already exists");
    }
    @Transactional
    public void addInvitationPayload(UUID id, CustomerProfileData p, Instant now) {
        insertPayload(id, p, now);
    }
    @Transactional
    public void transferInvitationPayload(UUID oldId, UUID newId, Instant now) {
        int count = jdbc.update("""
            UPDATE customer_invitation_data SET invitation_id = ?, updated_at = ? WHERE invitation_id = ?
            """, newId, utc(now), oldId);
        if (count != 1) throw new IllegalStateException("customer invitation payload missing");
    }
    @Transactional
    public void createProfileFromInvitation(UUID invitationId, UUID accountId, Instant now) {
        int count = jdbc.update("""
            INSERT INTO customer_profile (user_id, company_name, nip, billing_street, billing_building_number,
                billing_unit_number, billing_postal_code, billing_city, billing_country, phone, created_at, updated_at)
            SELECT ?, company_name, nip, billing_street, billing_building_number, billing_unit_number,
                billing_postal_code, billing_city, billing_country, phone, ?, ?
            FROM customer_invitation_data WHERE invitation_id = ?
            """, accountId, utc(now), utc(now), invitationId);
        if (count != 1) throw new IllegalStateException("customer invitation payload missing");
        if (jdbc.update("DELETE FROM customer_invitation_data WHERE invitation_id = ?", invitationId) != 1)
            throw new IllegalStateException("customer invitation payload consumption failed");
    }
    @Transactional
    public void update(UUID id, CustomerProfileData p, Instant now) {
        var a = p.billingAddress();
        int count = jdbc.update("""
            UPDATE customer_profile SET company_name=?, nip=?, billing_street=?, billing_building_number=?,
                billing_unit_number=?, billing_postal_code=?, billing_city=?, billing_country=?, phone=?, updated_at=?
            WHERE user_id=?
            """, p.companyName(), p.nip().value(), a.street(), a.buildingNumber(), a.unitNumber(),
            a.postalCode().value(), a.city(), a.country(), phone(p), utc(now), id);
        if (count != 1) throw new CustomerNotFoundException();
    }
    @Override @Transactional(readOnly = true)
    public CustomerProfileData billingDataForOrder(UUID customerId) {
        return detail(customerId).map(Detail::profile).orElseThrow(CustomerNotFoundException::new);
    }
    private void insertPayload(UUID id, CustomerProfileData p, Instant now) {
        var a = p.billingAddress();
        jdbc.update("""
            INSERT INTO customer_invitation_data (invitation_id, company_name, nip, billing_street,
                billing_building_number, billing_unit_number, billing_postal_code, billing_city,
                billing_country, phone, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, p.companyName(), p.nip().value(), a.street(), a.buildingNumber(), a.unitNumber(),
                a.postalCode().value(), a.city(), a.country(), phone(p), utc(now), utc(now));
    }
    private static CustomerProfileData profile(ResultSet rs) throws SQLException {
        return CustomerProfileData.of(rs.getString("company_name"), rs.getString("nip"),
                rs.getString("billing_street"), rs.getString("billing_building_number"),
                rs.getString("billing_unit_number"), rs.getString("billing_postal_code"),
                rs.getString("billing_city"), rs.getString("billing_country"), rs.getString("phone"));
    }
    private static String phone(CustomerProfileData p) { return p.phone() == null ? null : p.phone().value(); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
    private static OffsetDateTime utc(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
