"use strict";

document.getElementById("passkey-login").addEventListener("click", async () => {
    const error = document.getElementById("login-error");
    error.hidden = true;
    try {
        window.location.href = await WebAuthn.authenticate();
    } catch (e) {
        console.error(e);
        error.hidden = false;
    }
});
