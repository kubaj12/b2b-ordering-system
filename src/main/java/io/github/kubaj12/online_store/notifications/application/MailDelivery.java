package io.github.kubaj12.online_store.notifications.application;

/** Outbound port for delivering one already-rendered email to one recipient. */
public interface MailDelivery {

	void deliver(OutgoingMail mail);

}
