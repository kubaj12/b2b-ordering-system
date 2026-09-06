package io.github.kubaj12.online_store.testsupport;

import org.junit.jupiter.api.Test;

import io.github.kubaj12.online_store.notifications.application.MailDeliveryException;
import io.github.kubaj12.online_store.notifications.application.OutgoingMail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordingMailDeliveryTests {

	private static final OutgoingMail FIRST_MAIL = new OutgoingMail(
			"customer@example.test",
			"Pierwsza wiadomość",
			"Treść tekstowa",
			"<p>Treść HTML</p>"
	);
	private static final OutgoingMail SECOND_MAIL = new OutgoingMail(
			"employee@example.test",
			"Druga wiadomość",
			"Druga treść",
			""
	);

	private final RecordingMailDelivery delivery = new RecordingMailDelivery();

	@Test
	void recordsAttemptsAndSuccessfulDeliveriesInOrder() {
		delivery.deliver(FIRST_MAIL);
		delivery.deliver(SECOND_MAIL);

		assertThat(delivery.attempts()).containsExactly(FIRST_MAIL, SECOND_MAIL);
		assertThat(delivery.delivered()).containsExactly(FIRST_MAIL, SECOND_MAIL);
	}

	@Test
	void scriptsClassifiedFailuresWithoutCountingThemAsDelivered() {
		delivery.failNextTemporarily();
		delivery.failNextPermanently();

		assertThatThrownBy(() -> delivery.deliver(FIRST_MAIL))
				.isInstanceOfSatisfying(MailDeliveryException.class, failure ->
						assertThat(failure.kind()).isEqualTo(MailDeliveryException.Kind.TEMPORARY));
		assertThatThrownBy(() -> delivery.deliver(SECOND_MAIL))
				.isInstanceOfSatisfying(MailDeliveryException.class, failure ->
						assertThat(failure.kind()).isEqualTo(MailDeliveryException.Kind.PERMANENT));

		assertThat(delivery.attempts()).containsExactly(FIRST_MAIL, SECOND_MAIL);
		assertThat(delivery.delivered()).isEmpty();
	}

	@Test
	void resetClearsMessagesAndScriptedBehavior() {
		delivery.deliver(FIRST_MAIL);
		delivery.failNextPermanently();

		delivery.reset();
		delivery.deliver(SECOND_MAIL);

		assertThat(delivery.attempts()).containsExactly(SECOND_MAIL);
		assertThat(delivery.delivered()).containsExactly(SECOND_MAIL);
	}

	@Test
	void redactsBodiesFromDiagnosticText() {
		assertThat(FIRST_MAIL.toString())
				.contains("customer@example.test", "plainTextBody=<redacted>", "htmlBody=<redacted>")
				.doesNotContain("Treść tekstowa", "Treść HTML");
	}

}
