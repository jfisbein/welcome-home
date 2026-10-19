"use strict";

const MESSAGES = {
    "opened": "Puerta abierta: empújala.",
    "not-confirmed": "Hemos pedido a la cerradura que abra, pero no lo ha confirmado. Prueba a empujar la puerta; si no abre, inténtalo de nuevo.",
    "lock-failed": "La cerradura no ha podido abrir. Inténtalo de nuevo; si sigue fallando, avisa a quien te ha enviado el enlace.",
    "night-mode": "La cerradura está en modo nocturno y no abre a distancia. Avisa a quien te ha enviado el enlace.",
    "invalid": "Este enlace no abre ninguna puerta.",
    "too-many-attempts": "Demasiados intentos. Espera un minuto y vuelve a probar.",
    "revoked": "Este enlace se ha revocado.",
    "not-yet-valid": "Este enlace todavía no es válido.",
    "expired": "Este enlace ha caducado.",
    "unavailable": "Esta puerta no está disponible ahora mismo. Avisa a quien te ha enviado el enlace.",
    "missing-permission": "Esta puerta no está disponible ahora mismo. Avisa a quien te ha enviado el enlace.",
    "nuki-error": "La cerradura no responde. Inténtalo de nuevo en unos segundos."
};

const button = document.getElementById("open");
const message = document.getElementById("open-result");

button.addEventListener("click", async () => {
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    button.textContent = "Abriendo…";
    // Without an answer from the app, the request may still have reached the lock: the guest is told to push
    // the door before trying again, as after an unconfirmed opening.
    let result = "not-confirmed";
    try {
        const response = await fetch("/api/open", {
            method: "POST",
            // No CSRF token: the link's token is the credential, and the page keeps no session to hold one.
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({token: window.location.pathname.split("/").pop()})
        });
        result = (await response.json()).result || result;
    } catch (e) {
        console.error(e);
    }
    message.textContent = MESSAGES[result] || MESSAGES["not-confirmed"];
    message.hidden = false;
    button.removeAttribute("aria-busy");
    button.textContent = "Abrir";
    // Trying again makes sense only when the lock or Nuki failed, or the opening was not confirmed.
    button.disabled = !["nuki-error", "too-many-attempts", "not-confirmed", "lock-failed"].includes(result);
});
