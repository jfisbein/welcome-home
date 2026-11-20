"use strict";

// What can go wrong, by the browser's error name: the page carries it, in the owner's language.
const ADD_MESSAGES = Object.fromEntries([...document.querySelectorAll("#add-passkey-messages [data-error]")]
    .map(element => [element.dataset.error, element.textContent]));

document.getElementById("add-passkey").addEventListener("submit", async event => {
    event.preventDefault();
    const error = document.getElementById("add-passkey-error");
    error.hidden = true;
    const label = new FormData(event.target).get("label").trim();
    const button = event.target.querySelector("button[type=submit]");
    WebAuthn.busy(button, true);
    try {
        await WebAuthn.add(label);
        window.location.href = "/settings/passkeys?added";
    } catch (e) {
        console.error(e);
        if (e.code === "sign-in-not-recent") {
            // Signed in too long ago to add a passkey: confirm with one first, and come back.
            window.location.href = "/settings/confirm?for=passkeys";
            return;
        }
        error.textContent = ADD_MESSAGES[e.name] || ADD_MESSAGES["default"];
        error.hidden = false;
        WebAuthn.busy(button, false);
    }
});
