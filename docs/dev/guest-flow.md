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
| Guests slide to open (a native `input type="range"`), not press a button | A touch by mistake, in a pocket or while scrolling, must not open the door. A native range, so keyboards and screen readers work it; `open.js` undoes a jump to where the track was touched and moves a quarter per key. After an opening it works again a minute later, its thumb back at the start after 2 seconds but off until then, so that a touch by mistake with the page still open does not open again; after a failure at once; after expired, revoked or invalid never. |

## Tests

- **Guest flow**: `OpenDoorTests` (order of checks, results, confirmation from the log or Nuki's webhook, the Advanced API's fallback to the plain action, concurrency, client IP, the building entrance, what an opening tells of a lock that Nuki reports offline), `LockMutexTests` (one action per lock), `TokenLoggingTests` (tokens and PINs never logged), `GuestPageTests` (a GET never calls the lock provider; one slider per door; a link's schedule; what sliding keeps).
- **Where the guest is**: `DoorDistanceTests` (the distance, the rule and its accuracy, the stricter mode, an unknown position), `LocationCheckTests` (what a slide comes to by distance, before anything reaches Nuki, the entrance's position, the history), `GuestLocationTests` in Chromium (the position sent, the 10 seconds' wait and the second slide, a refusal and the fresh position after it).
