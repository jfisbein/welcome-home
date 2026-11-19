"use strict";

// A button with data-copy="<input id>" copies that input's value; one with data-copy-text copies that text, and
// after it, on a line of its own, the link of its data-copy-url if it has one. Once copied, it says what its
// data-copied says.
document.querySelectorAll("[data-copy], [data-copy-text]").forEach(button => {
    button.addEventListener("click", async () => {
        const input = button.dataset.copy ? document.getElementById(button.dataset.copy) : null;
        const text = button.dataset.copyUrl ? button.dataset.copyText + "\n" + button.dataset.copyUrl
            : button.dataset.copyText;
        try {
            await navigator.clipboard.writeText(input ? input.value : text);
            button.textContent = button.dataset.copied;
        } catch (e) {
            console.error(e);
            if (input) {
                input.select();
            }
        }
    });
});
