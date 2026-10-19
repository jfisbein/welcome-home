"use strict";

// What can go wrong, by the browser's error name: the page carries it, in the owner's language.
const ADD_MESSAGES = Object.fromEntries([...document.querySelectorAll("#add-passkey-messages [data-error]")]
    .map(element => [element.dataset.error, element.textContent]));

document.getElementById("add-passkey").addEventListener("submit", async event => {
    event.preventDefault();
    const error = document.getElementById("add-passkey-error");
    error.hidden = true;
    const label = new FormData(event.target).get("label").trim();
    try {
        await WebAuthn.add(label);
        window.location.href = "/passkeys?added";
    } catch (e) {
        console.error(e);
        error.textContent = ADD_MESSAGES[e.name] || ADD_MESSAGES["default"];
        error.hidden = false;
    }
});
