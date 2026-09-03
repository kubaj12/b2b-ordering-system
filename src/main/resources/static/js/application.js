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

    document.addEventListener("DOMContentLoaded", () => focusValidationSummary(document));
    document.addEventListener("htmx:afterSwap", (event) => focusValidationSummary(event.detail.target));
})();
