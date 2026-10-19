"use strict";

// A button with data-copy="<input id>" copies that input's value; one with data-copy-text copies that text.
// Once copied, it says what its data-copied says.
document.querySelectorAll("[data-copy], [data-copy-text]").forEach(button => {
    button.addEventListener("click", async () => {
        const input = button.dataset.copy ? document.getElementById(button.dataset.copy) : null;
        try {
            await navigator.clipboard.writeText(input ? input.value : button.dataset.copyText);
            button.textContent = button.dataset.copied;
        } catch (e) {
            console.error(e);
            if (input) {
                input.select();
            }
        }
    });
});
