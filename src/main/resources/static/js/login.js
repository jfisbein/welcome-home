"use strict";

// While the browser asks for the passkey the button does nothing: a second press would start a second ceremony,
// which makes the first one fail.
document.getElementById("passkey-login").addEventListener("click", async event => {
    const button = event.currentTarget;
    const error = document.getElementById("login-error");
    error.hidden = true;
    WebAuthn.busy(button, true);
    try {
        const next = await WebAuthn.authenticate();
        // The page that asks to confirm before a change says where to go back to; the sign-in page does not.
        window.location.href = button.dataset.continue || next;
    } catch (e) {
        console.error(e);
        error.hidden = false;
        WebAuthn.busy(button, false);
    }
});
