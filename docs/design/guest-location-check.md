# Checking where a guest is before opening

Status: proposal, 2026-11-04. Nothing here is built yet.

## Goal

A guest must not open a door by mistake when they are not in front of it: sliding at home to see whether the link works, sliding on the link of another lock they also have, or sliding a day early. It matters more than it seems: some doors, the owner's among them, swing open a few degrees when unlatched, so the lock cannot lock them again from afar, and a door opened "to try" stays open until someone closes it. The goal is to catch those mistakes, not to stop someone determined: a browser's position can be faked, and whoever holds a link can open with it anyway. So the check never leaves a guest standing at the door unable to open: when it doubts, it asks them to confirm.

What already guards against mistakes: the guest slides to open, so a touch by mistake does nothing; the slider stays off for a minute after an opening; the page says to slide when in front of the door; a link with an entrance names each door. And if a door is unlatched by mistake, Nuki's Auto Lock locks it again, which the app warns about when a lock does not have it (unless the owner marked that relocking is handled elsewhere).

## Options

| Option | Verdict |
|--------|---------|
| The browser's position (Geolocation API), compared with the lock's | **Chosen**: every phone browser has it, behind a permission prompt |
| Bluetooth: the phone finds the Nuki nearby (Web Bluetooth) | Rejected: no iPhone can, and Android asks the guest to pick the device each time |
| A QR code or NFC tag at the door, scanned with the link open | Deferred: the best proof of being there, but a sticker to place and a second step for every guest |
| The door counted as near when the same link opened its entrance minutes before | Deferred: a signal to add later, for locks with an Opener |

### Web Bluetooth, why not

From MDN's compatibility data, read on 2026-11-04:

- Safari has no Web Bluetooth, and every browser on iOS is Safari's engine, so it would leave out every iPhone. Firefox has none either.
- Chrome, on Android too, has `requestDevice`, which needs a user gesture and shows a chooser where the guest picks the device. `getDevices`, which would let the page see a device it was granted before without the chooser, is behind a flag. Nothing that notices a device nearby without the chooser (`watchAdvertisements`, `requestLEScan`) is in MDN's data at all.
- Seeing the lock in the chooser would prove the phone is within Bluetooth range, some 10 m, which is what we want; but picking "Nuki_1A2B3C" from a list, every time, is a step no guest would understand. Whether a lock paired with a Bridge, or on Wi-Fi, still advertises itself was not checked.

## Design

### The lock's position and the check's mode

New nullable columns on `lock`:

- `latitude`, `longitude` (double precision): where the lock is.
- `location_check` (text, default `OFF`): `OFF`, or `CONFIRM_WHEN_FAR`.
- `location_radius_m` (integer, default 150): how far counts as near.

Where the position comes from:

- **Nuki**, which reports `config.latitude` and `config.longitude` for each device (`GET /smartlock`, already read for the time zone), when the owner set the lock's location in the Nuki app. Import takes them, and the daily sync fills them only where the lock has none, as for the time zone: the owner's correction stays. 0, 0 counts as none.
- **The owner**, in the lock's "Estado y ajustes": a "Usar mi ubicación actual" button, for when they are at the door, which fills two fields they can also type in. No map, which would need a library and loosen the content security policy.

The check cannot be turned on without a position. An Opener has its own position; when it has none, its link's lock's position stands in for it, as an entrance is rarely more than tens of metres from the door.

### On the guest page

The page carries only whether the check is on (`data-location-check`), never the lock's position: a leaked link must not tell where the door is. The guest's position goes to the server, which decides.

1. **When to ask.** On load, the page asks the Permissions API whether geolocation is granted (Chrome, Firefox, and Safari since 16). If it is, it requests the position at once in the background, so that it is ready when the guest slides. If the browser would prompt, it asks only once the guest has slid to the end, so that the prompt follows their gesture. If it is denied, it does not ask.
2. **How.** `getCurrentPosition` with `enableHighAccuracy: true`, `timeout: 8000`, `maximumAge: 60000`. The slider says "Comprobando dónde estás…" meanwhile.
3. **What is sent.** `POST /api/open` adds `position: {latitude, longitude, accuracy}`, or `position: null` with `positionError` (`denied`, `unavailable`, `timeout` or `unsupported`), and `confirmed: false`.
4. **Asked to confirm.** The answer may be the new outcome `confirm-location`, with the distance when there was a position. The page then makes the guest stop and think before a second slide:
   - It says what sliding will do, in plain words. Far: "Parece que estás a unos 3 km de la puerta. Si deslizas, la puerta se abrirá de verdad aunque no estés delante, y puede quedarse abierta. Desliza solo si estás delante de ella." Without a position: "No hemos podido comprobar dónde estás. Si deslizas, la puerta se abrirá de verdad. Desliza solo si estás delante de ella."
   - The slider stays off for 10 seconds, its track counting them down ("Podrás deslizar en 10 s"), and then reads "Desliza para abrir igualmente". The message is in a `role="alert"` element, so screen readers say it, and the countdown is not announced each second.
   - Sliding again sends `confirmed: true`. A second slide rather than a button keeps the rule that a touch by mistake opens nothing, and the wait keeps a guest from sliding twice out of habit.
   - The wait is the page's: the server only asks for `confirmed`. A guest who reloads the page slides, is asked again and waits again, as the confirmation lives only in that page.
   - A guest who refused the permission waits 10 seconds at every opening. It is the price of not knowing where they are; the page asks for the permission saying why, which should make it rare.
5. **The notice.** `guest.privacy` adds that sliding checks how far the guest is from the door, without keeping where they are.

### On the server

`OpenDoor` checks the position after the link's own checks (MAC, state, schedule), so that an expired link is refused as before without asking anything, and before the unlatch. Only when the door's lock has the check on and a position:

- **Far**, if `distance − accuracy > radius`: answer `confirm-location` with the distance, unless `confirmed`.
- **Unknown**, without a position: answer `confirm-location`, unless `confirmed`.
- **Otherwise**, open. With a poor accuracy (a few hundred metres indoors) the rule cannot tell, and does not bother the guest: the mistake to catch is being kilometres away.

The distance is great-circle (haversine), rounded to 10 m. The position is never logged: `OpenRequest.toString` leaves it out, as it leaves out the token.

`confirm-location` is an `AccessOutcome` of result `DENIED`: it is recorded, counts for the rate limits (a far slide and its confirmation are two attempts), and sends no email, as the guest's own mistake was caught. An opening after confirming is recorded as any other, with its distance.

### What is kept

On `access_event`, nullable: `distance_m` and `accuracy_m` (integers), and `location_confirmed` (boolean). Never the guest's latitude and longitude. A distance to a known door says where someone was, so the nightly purge forgets these three with the IP address, after `welcomehome.audit.ip-retention`; `app_user` gets `UPDATE` on them, as on `ip`.

### What the owner sees

- The history's origin shows the distance next to the country ("a 3 km"), and the outcome "No abierta: lejos de la puerta" or "No abierta: sin ubicación".
- An opening the guest confirmed while far, or without a position, is emailed to the owner whatever their preferences, as an account email is: "Casa: Ana ha abierto la puerta a unos 3 km de ella", saying that the door may have been left open. It is the case where the owner may need to act, as the lock cannot close a door left ajar. Openings near the door email as before, by preference.

### Compatibility

Within v2. The migrations only add nullable columns, columns with a default, and grants. The release before ignores them and never asks for a position; rolled back to it, no lock checks anything. A guest page loaded from it sends no position, which the new release, with the check on, takes as unknown and asks to confirm. The release before shows `confirm-location` in the history as its code, as it does any outcome it does not know.

## Testing

- The rule: near, far, accuracy wider than the distance, no position, a radius from the settings; the distance against known pairs of points.
- `OpenDoorTests`: near opens; far and unknown ask; confirmed opens and records it; the check off ignores any position; an expired or revoked link is refused without asking; the entrance uses the Opener's position or the lock's; both attempts count for the rate limit; no position reaches the logs.
- `GuestSlideTests`, in Chromium: Playwright grants or denies the permission and sets the position (`setGeolocation`), so the page's whole flow runs: the message, the slider off during the countdown, on afterwards, and the second slide. Playwright's clock (`page.clock`) runs the 10 seconds without waiting them.
- `NotificationTests`: an opening confirmed far, or without a position, emails the owner with preferences off; one near does not when they are.
- The lock's settings (the fields, refused coordinates, no check without a position), the import of Nuki's coordinates (WireMock), the purge, and the bundles in five languages.
- On real phones, before turning it on for anyone but the owner: iPhone Safari, Android Chrome, and the browsers inside WhatsApp and Telegram on both, at the door and from a kilometre away.

## Rolling it out

Off for every lock. The owner turns it on for their own, with a test link, and goes through the phones above; then decides whether new locks start with it on, and the radius.

## Work

Medium to large: two migrations; the lock's settings page and the sync; `OpenDoor`, `open.js` and the guest page; the history and the email; texts in five languages; tests as above. Some three to four pull requests' worth, the lock's position first, as it is useful to see before any check uses it.

## Open questions

Only a phone will tell:

- How often Safari on iOS asks again: once per site, every visit, or as the iPhone's settings say.
- What the browsers inside WhatsApp and Telegram do: Android's in-app browsers may refuse geolocation without asking, which the design takes as unknown.
- How precise the position is at the actual door, indoors.
- Whether Nuki has a position for the owner's lock, and how close it is.

To decide after trying it:

- The radius, 150 m to start, and the wait, 10 seconds.
- A stricter mode per lock, which refuses a guest who is clearly far rather than asking, leaving the confirmation only for an unknown position. It would suit a door that stays ajar when opened, but it is a dead end for a guest at the door whose phone places them wrong: they would have to call the owner.
- Whether an unknown position should open at once rather than ask, if asking turns out to be frequent.
- Whether to add the entrance-then-door signal, so that a guest who opened the entrance minutes before is never asked at the door.
