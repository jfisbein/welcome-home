"use strict";

// What can go wrong, by the server's error code: the page carries it, in the visitor's language.
const MESSAGES = Object.fromEntries([...document.querySelectorAll("#registration-messages [data-error]")]
    .map(element => [element.dataset.error, element.textContent]));

document.getElementById("registration").addEventListener("submit", async event => {
    event.preventDefault();
    const error = document.getElementById("registration-error");
    error.hidden = true;
    // A passkey recovery link has no email field: the link names its owner.
    const email = new FormData(event.target).get("email");
    try {
        window.location.href = await WebAuthn.register(window.location.pathname, email);
    } catch (e) {
        console.error(e);
        error.textContent = MESSAGES[e.code] || MESSAGES["passkey"];
        error.hidden = false;
    }
});
