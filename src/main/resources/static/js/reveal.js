"use strict";

// An eye beside a password field that shows what is typed and hides it again. The button starts hidden: without
// this script the field stays a password field, which is the safe way to leave it. Its texts come from the page.
for (const button of document.querySelectorAll("button[data-reveal]")) {
    const field = document.getElementById(button.dataset.reveal);
    if (!field) {
        continue;
    }
    const open = button.querySelector(".eye-open");
    const closed = button.querySelector(".eye-closed");
    button.hidden = false;
    button.addEventListener("click", () => {
        const showing = field.type === "password";
        field.type = showing ? "text" : "password";
        button.setAttribute("aria-pressed", String(showing));
        button.setAttribute("aria-label", showing ? button.dataset.hide : button.dataset.show);
        open.hidden = showing;
        closed.hidden = !showing;
    });
}
