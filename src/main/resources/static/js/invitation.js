"use strict";

const MESSAGES = {
    "invitation": "Este enlace ya se ha usado o ha caducado.",
    "email": "Revisa el email: no parece válido.",
    "email-taken": "Ya hay una cuenta con este email.",
    "expired": "El registro ha caducado. Inténtalo de nuevo.",
    "passkey": "No se ha podido crear la passkey. Inténtalo de nuevo."
};

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
