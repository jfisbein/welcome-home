"use strict";

// The link form's schedule. A day not ticked offers neither its hours nor its whole day: CSS keeps a pointer off them
// and this keeps a keyboard off, without emptying the hours, which go on being sent. The button copies the first ticked
// day's hours, or else Monday's, to every day, and its whole day to the days ticked, so that hours shared by most days
// need typing once. Without JavaScript the button stays hidden and each day is typed.
const scheduleDays = [...document.querySelectorAll(".schedule-day")].map(row => ({
    day: row.querySelector("input[name^=day-]"),
    allDay: row.querySelector("input[name^=allday-]"),
    opens: row.querySelector("input[name^=opens-]"),
    closes: row.querySelector("input[name^=closes-]")
}));

function offerDay(row) {
    const off = !row.day.checked;
    if (off) {
        row.allDay.checked = false;
    }
    row.allDay.disabled = off;
    row.opens.readOnly = off;
    row.closes.readOnly = off;
}

for (const row of scheduleDays) {
    offerDay(row);
    row.day.addEventListener("change", () => offerDay(row));
}

const copySchedule = document.querySelector("[data-copy-schedule]");
if (copySchedule) {
    copySchedule.hidden = false;
    copySchedule.addEventListener("click", () => {
        const source = scheduleDays.find(row => row.day.checked) || scheduleDays[0];
        for (const row of scheduleDays) {
            row.opens.value = source.opens.value;
            row.closes.value = source.closes.value;
            row.allDay.checked = row.day.checked && source.allDay.checked;
        }
    });
}
