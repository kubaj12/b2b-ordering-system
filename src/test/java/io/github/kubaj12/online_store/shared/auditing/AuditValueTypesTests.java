package io.github.kubaj12.online_store.shared.auditing;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditValueTypesTests {

	private static final UUID TARGET_ID = UUID.fromString("eb9ba860-f365-435c-bfef-587a85bcf927");
	private static final AuditTargetType<UUID> USER_TARGET = AuditTargetType.uuid("user");
	private static final AuditActor ACTOR = new AuditActor(
			UUID.fromString("2a00b77a-3d20-4450-b6d5-e4f53cf70b95")
	);

	@Test
	void acceptsNamespacedEventAndTargetTypes() {
		var eventType = new AuditEventType<>(
				"catalog.base-price.changed",
				AuditTargetType.uuid("price_list_item")
		);

		assertThat(eventType.value()).isEqualTo("catalog.base-price.changed");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "blocked", "Identity.account.blocked", "identity.account blocked"})
	void rejectsInvalidEventTypes(String value) {
		assertThatThrownBy(() -> new AuditEventType<>(value, USER_TARGET))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " user", "user ", "USER"})
	void rejectsInvalidTargetTypes(String value) {
		assertThatThrownBy(() -> AuditTargetType.uuid(value))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNullNilAndNonPositiveTargetIds() {
		var numericTarget = AuditTargetType.positiveLong("user");
		var uuidEvent = new AuditEventType<>("identity.account.changed", USER_TARGET);
		var numericEvent = new AuditEventType<>("identity.numeric-account.changed", numericTarget);

		assertThatThrownBy(() -> uuidEvent.event(null, ACTOR))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> uuidEvent.event(new UUID(0, 0), ACTOR))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> numericEvent.event(0L, ACTOR))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void exposesNoArbitraryTextTargetFactoryOrPublicConstructor() {
		assertThat(Arrays.stream(AuditTargetType.class.getDeclaredConstructors()))
				.allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
		assertThat(Arrays.stream(AuditTargetType.class.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.filter(method -> Modifier.isStatic(method.getModifiers()))
				.map(method -> method.getName()))
				.containsExactlyInAnyOrder("uuid", "positiveLong");
	}

	@Test
	void createsEventsOnlyThroughTheirTypeDefinition() {
		var status = AuditField.enumeration("status", AccountStatus.class);
		var eventType = new AuditEventType<>("identity.account-status.changed", USER_TARGET, status);

		var event = eventType.event(
				TARGET_ID,
				ACTOR,
				status.change(AccountStatus.ACTIVE, AccountStatus.BLOCKED)
		);

		assertThat(event.type()).isSameAs(eventType);
		assertThat(event.actor()).isEqualTo(ACTOR);
		assertThat(Arrays.stream(AuditEvent.class.getDeclaredConstructors()))
				.noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers()));
	}

	@Test
	void permitsTypedCreationAndRemovalChangesButRejectsNoOps() {
		var status = AuditField.enumeration("status", AccountStatus.class);

		assertThat(status.change(null, AccountStatus.ACTIVE)).isNotNull();
		assertThat(status.change(AccountStatus.BLOCKED, null)).isNotNull();
		assertThatThrownBy(() -> status.change(AccountStatus.ACTIVE, AccountStatus.ACTIVE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must alter");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"password",
			"resetCode",
			"credential_hash",
			"clientSecret",
			"session.cookie",
			"invitationToken"
	})
	void rejectsSensitiveFieldDefinitions(String field) {
		assertThatThrownBy(() -> AuditField.flag(field))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("sensitive");
	}

	@Test
	void exposesNoTextFieldFactoryOrPublicConstructor() {
		assertThat(Arrays.stream(AuditField.class.getDeclaredConstructors()))
				.allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
		assertThat(Arrays.stream(AuditField.class.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.filter(method -> Modifier.isStatic(method.getModifiers()))
				.map(method -> method.getName()))
				.containsExactlyInAnyOrder("enumeration", "decimal", "integer", "flag");
	}

	@Test
	void rejectsAFieldNotAllowedByTheEventDefinition() {
		var status = AuditField.enumeration("status", AccountStatus.class);
		var role = AuditField.enumeration("role", StaffRole.class);
		var eventType = new AuditEventType<>("identity.account-status.changed", USER_TARGET, status);

		assertThatThrownBy(() -> eventType.event(
				TARGET_ID,
				ACTOR,
				role.change(StaffRole.EMPLOYEE, StaffRole.ADMIN)
		))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not allowed");
	}

	@Test
	void rejectsAnImpostorFieldEvenWhenItsNameMatchesTheAllowlist() {
		var allowedStatus = AuditField.enumeration("status", AccountStatus.class);
		var unrelatedStatus = AuditField.enumeration("status", OrderStatus.class);
		var eventType = new AuditEventType<>("identity.account-status.changed", USER_TARGET, allowedStatus);

		assertThatThrownBy(() -> eventType.event(
				TARGET_ID,
				ACTOR,
				unrelatedStatus.change(OrderStatus.SUBMITTED, OrderStatus.CLOSED)
		))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not allowed");
	}

	@Test
	void requiresMetadataWhenAnEventDeclaresFields() {
		var status = AuditField.enumeration("status", AccountStatus.class);
		var eventType = new AuditEventType<>("identity.account-status.changed", USER_TARGET, status);

		assertThatThrownBy(() -> eventType.event(TARGET_ID, ACTOR))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("requires change metadata");
	}

	@Test
	void rejectsDuplicateFieldChanges() {
		var status = AuditField.enumeration("status", AccountStatus.class);
		var eventType = new AuditEventType<>("identity.account-status.changed", USER_TARGET, status);
		var change = status.change(AccountStatus.ACTIVE, AccountStatus.BLOCKED);

		assertThatThrownBy(() -> eventType.event(TARGET_ID, ACTOR, change, change))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void enforcesDecimalScaleAndRangeBeforeBuildingAnEvent() {
		var vatRate = AuditField.decimal(
				"vatRate",
				2,
				BigDecimal.ZERO,
				new BigDecimal("100.00")
		);

		assertThat(vatRate.change(new BigDecimal("8.00"), new BigDecimal("23.00"))).isNotNull();
		assertThatThrownBy(() -> vatRate.change(new BigDecimal("23.00"), new BigDecimal("23.001")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("scale");
		assertThatThrownBy(() -> vatRate.change(new BigDecimal("23.00"), new BigDecimal("101.00")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("range");
	}

	@Test
	void enforcesIntegerRangeBeforeBuildingAnEvent() {
		var quantity = AuditField.integer("quantity", 0, 1_000_000);

		assertThat(quantity.change(1, 2)).isNotNull();
		assertThatThrownBy(() -> quantity.change(1, -1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("range");
	}

	private enum AccountStatus {
		ACTIVE,
		BLOCKED
	}

	private enum StaffRole {
		EMPLOYEE,
		ADMIN
	}

	private enum OrderStatus {
		SUBMITTED,
		CLOSED
	}

}
