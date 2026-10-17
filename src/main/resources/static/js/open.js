"use strict";

const MESSAGES = {
    "requested": "Hemos pedido a la cerradura que abra. Empuja la puerta cuando la oigas.",
    "invalid": "Este enlace no abre ninguna puerta.",
    "too-many-attempts": "Demasiados intentos. Espera un minuto y vuelve a probar.",
    "revoked": "Este enlace se ha revocado.",
    "not-yet-valid": "Este enlace todavía no es válido.",
    "expired": "Este enlace ha caducado.",
    "unavailable": "Esta puerta no está disponible ahora mismo. Avisa a quien te ha enviado el enlace.",
    "nuki-error": "La cerradura no responde. Inténtalo de nuevo en unos segundos."
};

const button = document.getElementById("open");
const message = document.getElementById("open-result");

button.addEventListener("click", async () => {
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    button.textContent = "Abriendo…";
    let result = "nuki-error";
    try {
        const response = await fetch("/api/open", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                [document.querySelector("meta[name=_csrf_header]").content]:
                    document.querySelector("meta[name=_csrf]").content
            },
            body: JSON.stringify({token: window.location.pathname.split("/").pop()})
        });
        result = (await response.json()).result || result;
    } catch (e) {
        console.error(e);
    }
    message.textContent = MESSAGES[result] || MESSAGES["nuki-error"];
    message.hidden = false;
    button.removeAttribute("aria-busy");
    button.textContent = "Abrir";
    // Trying again makes sense only when the lock or Nuki failed.
    button.disabled = !(result === "nuki-error" || result === "too-many-attempts");
});
