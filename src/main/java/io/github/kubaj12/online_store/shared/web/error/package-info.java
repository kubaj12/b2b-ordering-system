/**
 * Transport-safe web error and error-mapping primitives shared by module web adapters.
 *
 * <p>Expected errors carry localization keys and explicitly user-safe arguments. Routine form
 * binding errors remain owned by their feature controller and are rendered next to the form fields;
 * this package handles only errors that end the current browser request.</p>
 */
package io.github.kubaj12.online_store.shared.web.error;
