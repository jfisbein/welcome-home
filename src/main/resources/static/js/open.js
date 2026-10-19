"use strict";

// What the guest reads after pressing the button, by outcome: the page carries it, in the guest's language.
const MESSAGES = Object.fromEntries([...document.querySelectorAll("#open-messages [data-outcome]")]
    .map(element => [element.dataset.outcome, element.textContent]));

const button = document.getElementById("open");
const OPEN = button.textContent;
const message = document.getElementById("open-result");

button.addEventListener("click", async () => {
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    button.textContent = button.dataset.busyText;
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
    button.textContent = OPEN;
    // Trying again makes sense only when the lock or Nuki failed, or the opening was not confirmed.
    button.disabled = !["nuki-error", "too-many-attempts", "not-confirmed", "lock-failed"].includes(result);
});
