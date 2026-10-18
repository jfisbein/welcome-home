"use strict";

const ADD_MESSAGES = {
    "InvalidStateError": "Este dispositivo ya tiene una passkey para tu cuenta.",
    "NotAllowedError": "No se ha creado la passkey: se canceló o tardó demasiado.",
    "default": "No se ha podido añadir la passkey. Inténtalo de nuevo."
};

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
