"use strict";

// A button with data-copy="<input id>" copies that input's value.
document.querySelectorAll("[data-copy]").forEach(button => {
    button.addEventListener("click", async () => {
        const input = document.getElementById(button.dataset.copy);
        try {
            await navigator.clipboard.writeText(input.value);
            button.textContent = "Copiado";
        } catch (e) {
            console.error(e);
            input.select();
        }
    });
});
