package io.github.kubaj12.online_store.shared.web.validation;

/** Implemented by billing and delivery form objects validated as complete Polish addresses. */
public interface PolishAddressFields {
	String street();
	String buildingNumber();
	String unitNumber();
	String postalCode();
	String city();
	String country();
}
