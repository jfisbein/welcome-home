"use strict";

// What the guest reads after sliding to open, by outcome: the page carries it, in the guest's language.
const MESSAGES = Object.fromEntries([...document.querySelectorAll("#open-messages [data-outcome]")]
    .map(element => [element.dataset.outcome, element.textContent]));

// Outcomes worth trying again at once: the lock or Nuki failed, or the opening was not confirmed.
const RETRY_NOW = ["nuki-error", "too-many-attempts", "not-confirmed", "lock-failed"];
// Outcomes that cannot change while the page is open: the slider stays off.
const FINAL = ["expired", "revoked", "invalid"];
// After any other outcome, an opening above all, the slider works again after a while, for a guest who keeps
// the page open to come back later.
const ENABLE_AGAIN_AFTER_MS = 60 * 1000;
// How far from the thumb, as a share of the track, a pointer may start a slide: a touch on the track itself, by
// mistake, does not move the thumb there.
const THUMB_REACH = 0.15;

const slider = document.getElementById("open");
const label = document.querySelector(".slide-to-open .slide-label");
const SLIDE = label.textContent;
const message = document.getElementById("open-result");
const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

// Whether a pointer is down on the slider, and whether it started on the thumb: only then may it move it.
let touching = false;
let fromThumb = false;
// Where the thumb was before the pointer moved it, to undo a jump to where a pointer touched the track.
let lastValue = 0;

slider.addEventListener("pointerdown", event => {
    const bounds = slider.getBoundingClientRect();
    const share = (event.clientX - bounds.left) / bounds.width;
    touching = true;
    fromThumb = Math.abs(share - slider.valueAsNumber / 100) <= THUMB_REACH;
    lastValue = slider.valueAsNumber;
});

slider.addEventListener("input", () => {
    if (touching && !fromThumb) {
        // A pointer that touched the track away from the thumb: the browser moved the thumb there, undo it.
        slider.value = lastValue;
        return;
    }
    lastValue = slider.valueAsNumber;
});

// Keys move the thumb a quarter of the way, rather than the 1 % a step is, which keeps a slide smooth: four
// presses open.
const KEY_MOVES = {ArrowRight: 25, ArrowUp: 25, PageUp: 25, ArrowLeft: -25, ArrowDown: -25, PageDown: -25,
    Home: -100, End: 100};

slider.addEventListener("keydown", event => {
    const move = KEY_MOVES[event.key];
    if (move === undefined || slider.disabled) {
        return;
    }
    event.preventDefault();
    slider.value = Math.min(100, Math.max(0, slider.valueAsNumber + move));
    lastValue = slider.valueAsNumber;
    if (slider.valueAsNumber >= 100) {
        open();
    }
});

// Reaching the end opens. Screen readers on phones move the thumb by steps, each of them a change, so they open
// once they reach the end too.
slider.addEventListener("change", () => {
    if (slider.valueAsNumber >= 100 && !slider.disabled) {
        open();
    }
});

// A slide that ends short of the end goes back to the start.
for (const type of ["pointerup", "pointercancel"]) {
    slider.addEventListener(type, () => {
        touching = false;
        if (slider.valueAsNumber < 100) {
            slideBack();
        }
    });
}

function slideBack() {
    if (reducedMotion.matches) {
        slider.value = 0;
        return;
    }
    const step = () => {
        slider.value = Math.max(0, slider.valueAsNumber - 8);
        if (slider.valueAsNumber > 0 && !touching) {
            requestAnimationFrame(step);
        }
    };
    requestAnimationFrame(step);
}

function enable() {
    slider.value = 0;
    lastValue = 0;
    slider.disabled = false;
}

async function open() {
    slider.disabled = true;
    label.setAttribute("aria-busy", "true");
    label.textContent = label.dataset.busyText;
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
    label.removeAttribute("aria-busy");
    label.textContent = SLIDE;
    if (RETRY_NOW.includes(result)) {
        enable();
    } else if (!FINAL.includes(result)) {
        setTimeout(enable, ENABLE_AGAIN_AFTER_MS);
    }
}
