# Guest flow

Read when: changing the guest page, `POST /api/open`, the slide, the PIN asked of a guest, the check of where the guest is, rate limits or the per-lock mutex.

See also: [links](links.md) (PINs and schedules), [nuki](nuki.md) (the unlatch), [features](../features.md#guest-page).

## OpenDoor

**Guest flow** (`access.OpenDoor`): the order of its checks, and how an opening is confirmed, are in [docs/features.md](../features.md#guest-page), with a diagram. What must not change: a GET never acts on the lock, the MAC is checked before anything is read in the owner's context, and one action runs per lock at a time.

## Decisions

| Decision | Why |
|----------|-----|
| `/o/{token}` masked in logs, `Referrer-Policy: no-referrer`, `noindex` | Guest URLs are bearer credentials. |
| No CSRF token on `POST /api/open`, and no session for guests | The link's token in the body is the credential, which a forged request would need anyway; a CSRF token kept in the session went stale when a guest opened more than 30 minutes after loading the page. |
| Against opening by mistake from elsewhere (review items MIS-01, MIS-03, MIS-06, MIS-10, 2026-11-19): the page says above its sliders that sliding really opens the door; a link is copied and shared with words for its guest; a lock imported with a position checks where its guests are from the start; a position too vague to tell is no position | A guest who does not quite understand the link tries it at home on receiving it, and with the check off, as it was for every lock, that opened the door. The notice under the slider was small print, a shared link came with no word of what it does, an owner had to know of the check to turn it on, and a computer placed by its IP address within kilometres counted as near any door. Locks already imported keep their owner's choice and say on the lists of locks that their links open from anywhere. See [the check's design](../design/guest-location-check.md). |
| The guest page as a preview is the link's own URL with `?preview=1`, not an owner's route | An owner had no way to see their guest's page but the link itself, where a slide opens the door (review item MIS-07, 2026-11-19). The same controller and template draw it, so it cannot drift from what the guest sees, it needs no session, so the owner may open it on their phone, and it adds no route with an id. Sending nothing is the script's doing; that is enough, as whoever asks for the preview holds the link, which does more. An owner who sends the preview's URL by mistake sends a link that opens nothing, and the page says it is a preview. |
| Guests slide to open (a native `input type="range"`), not press a button | A touch by mistake, in a pocket or while scrolling, must not open the door. A native range, so keyboards and screen readers work it; `open.js` undoes a jump to where the track was touched and moves a quarter per key. After an opening it works again a minute later, its thumb back at the start after 2 seconds but off until then, so that a touch by mistake with the page still open does not open again; after a failure at once; after expired, revoked or invalid never. |

## Tests

- **Guest flow**: `OpenDoorTests` (order of checks, results, confirmation from the log or Nuki's webhook, the Advanced API's fallback to the plain action, concurrency, client IP, the building entrance, what an opening tells of a lock that Nuki reports offline), `LockMutexTests` (one action per lock), `TokenLoggingTests` (tokens and PINs never logged), `GuestPageTests` (a GET never calls the lock provider; one slider per door; a link's schedule; what sliding keeps; that sliding opens a real door, said before any slider; the page as a preview), `GuestSlideTests` in Chromium (a slide in a preview sends no request).
- **Where the guest is**: `DoorDistanceTests` (the distance, the rule and its accuracy, a position too vague to tell, the stricter mode, the strictest one, an unknown position), `LocationCheckTests` (what a slide comes to by distance, before anything reaches Nuki, the entrance's position, the history), `GuestLocationTests` in Chromium (the position sent, the 10 seconds' wait and the second slide, a refusal and the fresh position after it).
