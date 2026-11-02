"use strict";

// The link form's schedule: copies the hours of the first day ticked, or else Monday's, to every day, so that hours
// shared by most days need typing once. Without JavaScript the button stays hidden and each day is typed.
const copySchedule = document.querySelector("[data-copy-schedule]");
if (copySchedule) {
    copySchedule.hidden = false;
    copySchedule.addEventListener("click", () => {
        const days = [...document.querySelectorAll(".schedule-day")];
        const source = days.find(day => day.querySelector("input[type=checkbox]").checked) || days[0];
        const [opens, closes] = source.querySelectorAll("input[type=time]");
        for (const day of days) {
            const [dayOpens, dayCloses] = day.querySelectorAll("input[type=time]");
            dayOpens.value = opens.value;
            dayCloses.value = closes.value;
        }
    });
}
