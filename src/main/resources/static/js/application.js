(() => {
    "use strict";

    const focusValidationSummary = (root) => {
        const summary = root.matches?.("[data-validation-error-summary]")
            ? root
            : root.querySelector?.("[data-validation-error-summary]");
        if (summary) {
            summary.focus();
        }
    };

    const focusBrowserError = (root) => {
        const error = root.matches?.("[data-browser-error]")
            ? root
            : root.querySelector?.("[data-browser-error]");
        if (error) {
            error.focus();
        }
    };

    const csrfMetadata = () => ({
        token: document.querySelector('meta[name="_csrf"]')?.content,
        header: document.querySelector('meta[name="_csrf_header"]')?.content
    });

    const configureVariantForms = (root) => {
        root.querySelectorAll?.("[data-variant-assignment]").forEach((form) => {
            const definition = form.querySelector("[data-variant-definition]");
            const value = form.querySelector("[data-variant-value]");
            if (!definition || !value || form.dataset.variantReady === "true") return;
            const filterValues = () => {
                const selectedDefinition = definition.value;
                value.querySelectorAll("option[data-definition-id]").forEach((option) => {
                    option.disabled = !selectedDefinition || option.dataset.definitionId !== selectedDefinition;
                });
                value.value = "";
            };
            definition.addEventListener("change", filterValues);
            form.dataset.variantReady = "true";
            filterValues();
        });
    };

    document.addEventListener("htmx:configRequest", (event) => {
        const verb = event.detail.verb?.toUpperCase();
        if (["GET", "HEAD", "OPTIONS", "TRACE"].includes(verb)) {
            return;
        }

        const csrf = csrfMetadata();
        if (csrf.token && csrf.header) {
            event.detail.headers[csrf.header] = csrf.token;
        }
    });

    document.addEventListener("htmx:beforeSwap", (event) => {
        if (event.detail.xhr.getResponseHeader("X-B2B-Handled-Error") === "true") {
            event.detail.shouldSwap = true;
        }
    });

    document.addEventListener("htmx:historyCacheMissLoadError", (event) => {
        const redirect = event.detail.xhr.getResponseHeader("HX-Redirect");
        if (!redirect) {
            return;
        }

        const target = new URL(redirect, window.location.href);
        if (target.origin === window.location.origin) {
            window.location.assign(target.href);
        }
    });

    document.addEventListener("DOMContentLoaded", () => {
        focusValidationSummary(document);
        configureVariantForms(document);
    });
    document.addEventListener("htmx:afterSwap", (event) => {
        focusValidationSummary(event.detail.target);
        focusBrowserError(event.detail.target);
        configureVariantForms(event.detail.target);
    });
})();
