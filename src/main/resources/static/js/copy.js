"use strict";

// A button with data-copy="<input id>" copies that input's value; one with data-copy-text copies that text.
document.querySelectorAll("[data-copy], [data-copy-text]").forEach(button => {
    button.addEventListener("click", async () => {
        const input = button.dataset.copy ? document.getElementById(button.dataset.copy) : null;
        try {
            await navigator.clipboard.writeText(input ? input.value : button.dataset.copyText);
            button.textContent = "Copiado";
        } catch (e) {
            console.error(e);
            if (input) {
                input.select();
            }
        }
    });
});
