"use strict";

// A form with data-confirm asks before it is sent, for actions that cannot be undone.
document.querySelectorAll("form[data-confirm]").forEach(form => {
    form.addEventListener("submit", event => {
        if (!window.confirm(form.dataset.confirm)) {
            event.preventDefault();
        }
    });
});
