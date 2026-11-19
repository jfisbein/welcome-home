"use strict";

// A button with data-copy="<input id>" copies that input's value; one with data-copy-text copies that text, and
// after it, on a line of its own, the link of its data-copy-url if it has one. Once copied, it says what its
// data-copied says for a moment, so that a second copy is seen too; when the browser refuses, what its
// data-copy-failed says, and an input is left selected to copy by hand.
const SAID_FOR_MS = 2000;

function say(button, text) {
    if (!text) {
        return;
    }
    button.dataset.label = button.dataset.label || button.textContent;
    button.textContent = text;
    window.clearTimeout(button.saying);
    button.saying = window.setTimeout(() => {
        button.textContent = button.dataset.label;
    }, SAID_FOR_MS);
}

document.querySelectorAll("[data-copy], [data-copy-text]").forEach(button => {
    button.addEventListener("click", async () => {
        const input = button.dataset.copy ? document.getElementById(button.dataset.copy) : null;
        const text = button.dataset.copyUrl ? button.dataset.copyText + "\n" + button.dataset.copyUrl
            : button.dataset.copyText;
        try {
            await navigator.clipboard.writeText(input ? input.value : text);
            say(button, button.dataset.copied);
        } catch (e) {
            console.error(e);
            say(button, button.dataset.copyFailed);
            if (input) {
                input.select();
            }
        }
    });
});
