"use strict";

// Outcomes worth trying again at once: the lock or Nuki failed, or the opening was not confirmed.
const RETRY_NOW = ["nuki-error", "too-many-attempts", "not-confirmed", "lock-failed"];
// Outcomes that cannot change while the page is open: the slider stays off.
const FINAL = ["expired", "revoked", "invalid"];
// After any other outcome, an opening above all, the slider works again after a while, for a guest who keeps
// the page open to come back later. An Opener opens the entrance only for a few seconds: a guest who did not
// push in time may slide again sooner.
const ENABLE_AGAIN_AFTER_MS = 60 * 1000;
const OPENER_ENABLE_AGAIN_AFTER_MS = 10 * 1000;
// Meanwhile the thumb goes back to the start once the guest has seen it at the end, but the slider stays off, so
// that a touch by mistake right after an opening, with the page still open, does not open again.
const SLIDE_BACK_AFTER_MS = 2 * 1000;
// How far from the thumb, as a share of the track, a pointer may start a slide: a touch on the track itself, by
// mistake, does not move the thumb there.
const THUMB_REACH = 0.15;
// Keys move the thumb a quarter of the way, rather than the 1 % a step is, which keeps a slide smooth: four
// presses open.
const KEY_MOVES = {ArrowRight: 25, ArrowUp: 25, PageUp: 25, ArrowLeft: -25, ArrowDown: -25, PageDown: -25,
    Home: -100, End: 100};

const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
// For a door that checks where its guests are: how long a guest asked to confirm, or refused as far, waits before
// sliding again, long enough to read why; and how long the browser may take to say where the guest is, and how old
// a position it may give.
const LOCATION_WAIT_S = 10;
const POSITION_TIMEOUT_MS = 8 * 1000;
const POSITION_MAX_AGE_MS = 60 * 1000;

// Where the browser says the guest is, {latitude, longitude, accuracy}, or {error} when it does not say. Asked for
// on loading only when the browser already allows it, so that it is ready when the guest slides, and otherwise when
// they have slid, so that the browser's question follows their gesture.
let earlyPosition = null;
if (document.querySelector("input[data-location-check=true]") && navigator.permissions) {
    navigator.permissions.query({name: "geolocation"})
        .then(permission => {
            if (permission.state === "granted") {
                earlyPosition = guestPosition(POSITION_MAX_AGE_MS);
            }
        })
        .catch(() => {
        });
}

function guestPosition(maxAge) {
    return new Promise(resolve => {
        if (!("geolocation" in navigator)) {
            resolve({error: "unsupported"});
            return;
        }
        navigator.geolocation.getCurrentPosition(
            found => resolve({
                latitude: found.coords.latitude, longitude: found.coords.longitude, accuracy: found.coords.accuracy
            }),
            failure => resolve({error: {1: "denied", 2: "unavailable", 3: "timeout"}[failure.code] || "unavailable"}),
            {enableHighAccuracy: true, timeout: POSITION_TIMEOUT_MS, maximumAge: maxAge});
    });
}

// A distance as the guest reads it, in the page's language: "3,2 km", "300 m".
function distanceText(metres) {
    const language = document.documentElement.lang;
    return metres >= 1000
        ? new Intl.NumberFormat(language, {maximumFractionDigits: 1}).format(metres / 1000) + " km"
        : new Intl.NumberFormat(language).format(metres) + " m";
}

// Each door has its own slider: the link's lock, and the building entrance before it if it has one.
for (const door of document.querySelectorAll(".door")) {
    const slider = door.querySelector("input[type=range]");
    if (slider) {
        setUp(door, slider);
    }
}

function setUp(door, slider) {
    // What the guest reads after sliding to open, by outcome: the page carries it, in the guest's language.
    const messages = Object.fromEntries([...door.querySelectorAll(".open-messages [data-outcome]")]
        .map(element => [element.dataset.outcome, element.textContent]));
    const label = door.querySelector(".slide-label");
    const slide = label.textContent;
    const message = door.querySelector(".open-result");
    const enableAgainAfter = slider.dataset.opener === "true" ? OPENER_ENABLE_AGAIN_AFTER_MS : ENABLE_AGAIN_AFTER_MS;
    const checksLocation = slider.dataset.locationCheck === "true";
    // Once the guest is asked to confirm where they are, the next slide confirms; once refused as far, it asks the
    // browser afresh, in case it placed them wrong. Only within this page: a guest who reloads it is asked again.
    let confirming = false;
    let askAfresh = false;
    // For a link with a PIN: the form that asks for it after the slide, and the PIN while the guest is asked to
    // confirm where they are, so that they are not asked twice.
    const needsPin = slider.dataset.pinRequired === "true";
    const pinForm = door.querySelector(".pin-form");
    const pinField = pinForm ? pinForm.querySelector("input") : null;
    let rememberedPin = null;

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

    // Reaching the end opens. Screen readers on phones move the thumb by steps, each of them a change, so they
    // open once they reach the end too.
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

    // A guest asked to confirm, or refused as far, waits before sliding again, the track counting down the seconds.
    function waitThenEnable(then) {
        let left = LOCATION_WAIT_S;
        const tick = () => {
            if (left === 0) {
                label.textContent = then;
                enable();
                return;
            }
            label.textContent = label.dataset.waitText.replace("{0}", String(left));
            left--;
            setTimeout(tick, 1000);
        };
        tick();
    }

    // The slide is over and the link wants a PIN: the thumb stays at the end, off, while the guest types it.
    function askForPin() {
        slider.disabled = true;
        message.hidden = true;
        // The thumb stays at the end, over where the text is on a narrow screen: the text waits.
        label.textContent = "";
        pinField.removeAttribute("aria-invalid");
        pinForm.hidden = false;
        pinField.focus();
    }

    function stopAskingForPin() {
        pinForm.hidden = true;
        pinField.value = "";
        pinField.removeAttribute("aria-invalid");
    }

    if (needsPin) {
        pinForm.addEventListener("submit", event => {
            event.preventDefault();
            const typed = pinField.value.trim();
            if (typed === "") {
                pinField.focus();
                return;
            }
            open(typed);
        });
        pinForm.querySelector("[data-cancel-pin]").addEventListener("click", () => {
            stopAskingForPin();
            label.textContent = slide;
            enable();
        });
    }

    async function open(pin) {
        if (needsPin && pin === undefined) {
            if (rememberedPin === null) {
                askForPin();
                return;
            }
            pin = rememberedPin;
        }
        slider.disabled = true;
        label.setAttribute("aria-busy", "true");
        const request = {token: window.location.pathname.split("/").pop(), door: slider.dataset.door};
        if (checksLocation) {
            label.textContent = label.dataset.locatingText;
            const found = await (askAfresh ? guestPosition(0) : earlyPosition ?? guestPosition(POSITION_MAX_AGE_MS));
            earlyPosition = null;
            askAfresh = false;
            request.position = found.error ? null : found;
            request.positionError = found.error ?? null;
            request.confirmed = confirming;
        }
        if (needsPin) {
            request.pin = pin;
            for (const control of pinForm.elements) {
                control.disabled = true;
            }
        }
        label.textContent = label.dataset.busyText;
        // Without an answer from the app, the request may still have reached the lock: the guest is told to push
        // the door before trying again, as after an unconfirmed opening.
        let result = "not-confirmed";
        let distance = null;
        try {
            const response = await fetch("/api/open", {
                method: "POST",
                // No CSRF token: the link's token is the credential, and the page keeps no session to hold one.
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify(request)
            });
            const answer = await response.json();
            result = answer.result || result;
            distance = answer.distance ?? null;
        } catch (e) {
            console.error(e);
        }
        confirming = false;
        if (needsPin) {
            for (const control of pinForm.elements) {
                control.disabled = false;
            }
            if (result === "wrong-pin" || result === "pin-required") {
                // Typing it again needs no second slide.
                label.removeAttribute("aria-busy");
                label.textContent = "";
                message.textContent = messages[result];
                message.dataset.outcome = result;
                message.hidden = false;
                rememberedPin = null;
                pinField.value = "";
                pinField.setAttribute("aria-invalid", "true");
                pinField.focus();
                return;
            }
            stopAskingForPin();
            // The PIN passed: a guest asked to confirm where they are does not type it again.
            rememberedPin = result === "confirm-location" || result === "too-far" ? pin : null;
        }
        if (result === "confirm-location" || result === "too-far") {
            label.removeAttribute("aria-busy");
            message.textContent = distance === null ? messages["confirm-location-unknown"]
                : messages[result].replace("{0}", distanceText(distance));
            message.dataset.outcome = result;
            message.hidden = false;
            confirming = result === "confirm-location";
            askAfresh = result === "too-far";
            slider.value = 0;
            lastValue = 0;
            waitThenEnable(confirming ? label.dataset.anywayText : slide);
            return;
        }
        message.textContent = messages[result] || messages["not-confirmed"];
        message.dataset.outcome = result;
        message.hidden = false;
        label.removeAttribute("aria-busy");
        label.textContent = slide;
        if (RETRY_NOW.includes(result)) {
            enable();
        } else if (!FINAL.includes(result)) {
            setTimeout(slideBack, SLIDE_BACK_AFTER_MS);
            setTimeout(enable, enableAgainAfter);
        }
    }
}
