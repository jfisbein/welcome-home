"use strict";

// A button with data-share-url opens the device's share sheet (WhatsApp, Mail…). Hidden where the browser
// cannot share.
if (navigator.share) {
    document.querySelectorAll("[data-share-url]").forEach(button => {
        button.hidden = false;
        button.addEventListener("click", async () => {
            try {
                await navigator.share({title: button.dataset.shareTitle, url: button.dataset.shareUrl});
            } catch (e) {
                // The person closed the share sheet.
                console.debug(e);
            }
        });
    });
}
