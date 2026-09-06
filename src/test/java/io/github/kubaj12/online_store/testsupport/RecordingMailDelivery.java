package io.github.kubaj12.online_store.testsupport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.kubaj12.online_store.notifications.application.MailDelivery;
import io.github.kubaj12.online_store.notifications.application.MailDeliveryException;
import io.github.kubaj12.online_store.notifications.application.OutgoingMail;

/** Thread-safe fake that records ordered attempts and can script classified delivery failures. */
public final class RecordingMailDelivery implements MailDelivery {

	private final List<OutgoingMail> attempts = new ArrayList<>();
	private final List<OutgoingMail> delivered = new ArrayList<>();
	private final ArrayDeque<MailDeliveryException> scriptedFailures = new ArrayDeque<>();

	@Override
	public synchronized void deliver(OutgoingMail mail) {
		Objects.requireNonNull(mail, "mail must not be null");
		attempts.add(mail);
		MailDeliveryException failure = scriptedFailures.pollFirst();
		if (failure != null) {
			throw failure;
		}
		delivered.add(mail);
	}

	public synchronized void failNextTemporarily() {
		scriptedFailures.addLast(MailDeliveryException.temporary(null));
	}

	public synchronized void failNextPermanently() {
		scriptedFailures.addLast(MailDeliveryException.permanent(null));
	}

	public synchronized List<OutgoingMail> attempts() {
		return List.copyOf(attempts);
	}

	public synchronized List<OutgoingMail> delivered() {
		return List.copyOf(delivered);
	}

	public synchronized void reset() {
		attempts.clear();
		delivered.clear();
		scriptedFailures.clear();
	}

}
