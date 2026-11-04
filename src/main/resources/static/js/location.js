"use strict";

// The lock's position: the button fills it with the device's own, for an owner standing at the door, and says how
// precise it is. Its texts come from the button's data attributes. Without JavaScript or geolocation the button stays
// hidden, and the position is typed or pasted.
const locationButton = document.getElementById("use-my-location");
const coordinatesField = document.querySelector("input[name=coordinates]");
const locationStatus = document.getElementById("location-status");

function located(position) {
    const {latitude, longitude, accuracy} = position.coords;
    coordinatesField.value = latitude.toFixed(6) + ", " + longitude.toFixed(6);
    locationStatus.textContent = locationButton.dataset.found.replace("{0}", String(Math.round(accuracy)));
    locationButton.removeAttribute("aria-busy");
}

function notLocated() {
    locationStatus.textContent = locationButton.dataset.failed;
    locationButton.removeAttribute("aria-busy");
}

if (locationButton && coordinatesField && locationStatus && "geolocation" in navigator) {
    locationButton.hidden = false;
    locationButton.addEventListener("click", () => {
        locationButton.setAttribute("aria-busy", "true");
        locationStatus.textContent = locationButton.dataset.locating;
        navigator.geolocation.getCurrentPosition(located, notLocated,
            {enableHighAccuracy: true, timeout: 20000, maximumAge: 0});
    });
}
