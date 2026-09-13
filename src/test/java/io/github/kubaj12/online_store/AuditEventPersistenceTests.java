package io.github.kubaj12.online_store;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.kubaj12.online_store.shared.auditing.AuditActor;
import io.github.kubaj12.online_store.shared.auditing.AuditEvent;
import io.github.kubaj12.online_store.shared.auditing.AuditEventRecorder;
import io.github.kubaj12.online_store.shared.auditing.AuditEventType;
import io.github.kubaj12.online_store.shared.auditing.AuditField;
import io.github.kubaj12.online_store.shared.auditing.AuditFieldChange;
import io.github.kubaj12.online_store.shared.auditing.AuditTargetType;
import io.github.kubaj12.online_store.testsupport.IdentityDatabaseFixture;
import io.github.kubaj12.online_store.testsupport.PostgreSqlServiceTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEventPersistenceTests extends PostgreSqlServiceTestSupport {

	private static final Instant RECORDED_AT = Instant.parse("2026-08-24T12:34:56.123456Z");
	private static final UUID ACTING_USER_ID = UUID.fromString("f552e38c-cf2e-4be7-a96c-65b495dd28ea");
	private static final UUID SKU_ID = UUID.fromString("0198d278-0a67-7ee2-b69c-eac0906b208b");
	private static final AuditTargetType<UUID> SKU_TARGET = AuditTargetType.uuid("sku");
	private static final AuditField<BigDecimal> BASE_NET_PRICE = AuditField.decimal(
			"baseNetPrice",
			2,
			BigDecimal.ZERO,
			new BigDecimal("9999999999.99")
	);
	private static final AuditField<BigDecimal> VAT_RATE = AuditField.decimal(
			"vatRate",
			2,
			BigDecimal.ZERO,
			new BigDecimal("100.00")
	);
	private static final AuditEventType<UUID> SKU_PRICING_CHANGED = new AuditEventType<>(
			"catalog.sku-pricing.changed",
			SKU_TARGET,
			BASE_NET_PRICE,
			VAT_RATE
	);
	private static final AuditEventType<UUID> EMPTY_EVENT = new AuditEventType<>(
			"test.empty.recorded",
			SKU_TARGET
	);

	@Autowired
	private AuditEventRecorder recorder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@BeforeEach
	void setUpAuditActor() {
		testClock().set(RECORDED_AT);
		new IdentityDatabaseFixture(jdbcTemplate)
				.insertActiveUser(ACTING_USER_ID, "audit.employee@example.test", "EMPLOYEE", RECORDED_AT);
	}

	@Test
	void recordsACompleteEventAtTheInjectedUtcInstant() {
		var event = event(
				BASE_NET_PRICE.change(new BigDecimal("12.34"), new BigDecimal("15.00")),
				VAT_RATE.change(new BigDecimal("8.00"), new BigDecimal("23.00"))
		);

		transactionTemplate.executeWithoutResult(status -> recorder.record(event));

		StoredAuditEvent stored = jdbcTemplate.queryForObject("""
				SELECT
				event_type,
				target_type,
				target_id,
				acting_user_id,
				occurred_at,
				change_metadata #>> '{baseNetPrice,from}' AS old_price,
				change_metadata #>> '{baseNetPrice,to}' AS new_price,
				change_metadata #>> '{vatRate,to}' AS new_vat_rate
				FROM audit_event
				""", (resultSet, rowNumber) -> new StoredAuditEvent(
				resultSet.getString("event_type"),
				resultSet.getString("target_type"),
				resultSet.getString("target_id"),
				resultSet.getObject("acting_user_id", UUID.class),
				resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
				resultSet.getString("old_price"),
				resultSet.getString("new_price"),
				resultSet.getString("new_vat_rate")
		));

		assertThat(stored).isEqualTo(new StoredAuditEvent(
				"catalog.sku-pricing.changed",
				"sku",
				SKU_ID.toString(),
				ACTING_USER_ID,
				RECORDED_AT,
				"12.34",
				"15.00",
				"23.00"
		));
	}

	@Test
	void recordsExplicitlyEmptyMetadata() {
		transactionTemplate.executeWithoutResult(status -> recorder.record(EMPTY_EVENT.event(SKU_ID, actor())));

		Boolean isEmptyObject = jdbcTemplate.queryForObject(
				"SELECT change_metadata = '{}'::JSONB FROM audit_event",
				Boolean.class
		);

		assertThat(isEmptyObject).isTrue();
	}

	@Test
	void requiresAnExistingTransaction() {
		assertThatThrownBy(() -> recorder.record(EMPTY_EVENT.event(SKU_ID, actor())))
				.isInstanceOf(IllegalTransactionStateException.class);

		assertThat(eventCount()).isZero();
	}

	@Test
	void rollsBackTheAuditEventWithItsOwningOperation() {
		transactionTemplate.executeWithoutResult(status -> {
			recorder.record(event(BASE_NET_PRICE.change(
					new BigDecimal("12.34"),
					new BigDecimal("15.00")
			)));
			status.setRollbackOnly();
		});

		assertThat(eventCount()).isZero();
	}

	@Test
	void rejectsMetadataThatExceedsTheEncodedLimit() {
		var fields = new AuditField<?>[32];
		var changes = new AuditFieldChange<?>[32];
		BigDecimal oldValue = BigDecimal.TEN.pow(300).negate();
		BigDecimal newValue = BigDecimal.TEN.pow(300);
		for (int index = 0; index < 32; index++) {
			var field = AuditField.decimal("field" + index, 0, oldValue, newValue);
			fields[index] = field;
			changes[index] = field.change(oldValue, newValue);
		}
		var oversizedEventType = new AuditEventType<>("test.large.changed", SKU_TARGET, fields);
		var oversizedEvent = oversizedEventType.event(SKU_ID, actor(), changes);

		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
				status -> recorder.record(oversizedEvent)
		))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("encoded size");

		assertThat(eventCount()).isZero();
	}

	@Test
	void databaseRejectsNonObjectMetadata() {
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO audit_event (
					event_type,
					target_type,
					target_id,
					acting_user_id,
					occurred_at,
					change_metadata
				) VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
				""",
				"identity.account.blocked",
				"user",
				"42",
				ACTING_USER_ID,
				OffsetDateTime.ofInstant(RECORDED_AT, ZoneOffset.UTC),
				"[]"
		))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("audit_event_change_metadata_object");
	}

	@Test
	void databaseRejectsArbitraryTextTargetIds() {
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO audit_event (
					event_type,
					target_type,
					target_id,
					acting_user_id,
					occurred_at,
					change_metadata
				) VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
				""",
				"identity.password-reset.completed",
				"user",
				"raw-reset-token-must-not-be-a-target",
				ACTING_USER_ID,
				OffsetDateTime.ofInstant(RECORDED_AT, ZoneOffset.UTC),
				"{}"
		))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("audit_event_target_id_format");
	}

	@Test
	void databaseRejectsANewEventWithUnknownActor() {
		UUID unknownActorId = UUID.fromString("51edc87f-ee36-49cd-9a4d-efb41c0f28f8");

		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO audit_event (
					event_type,
					target_type,
					target_id,
					acting_user_id,
					occurred_at,
					change_metadata
				) VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
				""",
				"identity.account.blocked",
				"user",
				"42",
				unknownActorId,
				OffsetDateTime.ofInstant(RECORDED_AT, ZoneOffset.UTC),
				"{}"
		))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("audit_event_acting_user_id_fk");
	}

	@Test
	void databaseRejectsDeletingAnAccountReferencedByAuditHistory() {
		transactionTemplate.executeWithoutResult(status -> recorder.record(EMPTY_EVENT.event(SKU_ID, actor())));

		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM identity_user WHERE id = ?", ACTING_USER_ID))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("audit_event_acting_user_id_fk");

		assertThat(eventCount()).isOne();
	}

	@Test
	void blockingAnAccountPreservesItsAuditHistory() {
		transactionTemplate.executeWithoutResult(status -> recorder.record(EMPTY_EVENT.event(SKU_ID, actor())));

		int updated = jdbcTemplate.update(
				"UPDATE identity_user SET status = 'BLOCKED', updated_at = ? WHERE id = ?",
				OffsetDateTime.ofInstant(RECORDED_AT.plusSeconds(60), ZoneOffset.UTC),
				ACTING_USER_ID
		);

		assertThat(updated).isOne();
		assertThat(eventCount()).isOne();
	}

	@Test
	void createsIndexesForExpectedAuditLookups() {
		List<String> indexNames = jdbcTemplate.queryForList("""
				SELECT indexname
				FROM pg_indexes
				WHERE schemaname = 'public'
				  AND tablename = 'audit_event'
				""", String.class);

		assertThat(indexNames).contains(
				"audit_event_target_occurred_at_idx",
				"audit_event_actor_occurred_at_idx",
				"audit_event_type_occurred_at_idx"
		);
	}

	private AuditEvent event(AuditFieldChange<?>... changes) {
		return SKU_PRICING_CHANGED.event(SKU_ID, actor(), changes);
	}

	private AuditActor actor() {
		return new AuditActor(ACTING_USER_ID);
	}

	private long eventCount() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class);
	}

	private record StoredAuditEvent(
			String eventType,
			String targetType,
			String targetId,
			UUID actingUserId,
			Instant occurredAt,
			String oldPrice,
			String newPrice,
			String newVatRate
	) {
	}

}
