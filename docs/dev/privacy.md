# Privacy and history

Read when: changing what is kept about guests or owners and for how long: attempts, IP addresses, owners' activity, purge jobs.

See also: [security](../security.md), [links](links.md), [architecture](architecture.md#jobs).

## Decisions

| Decision | Why |
|----------|-----|
| Owners' activity: sign-ins and changes to links, recorded from events on the request's thread, the link's change in its own transaction; an email, always, about a link that may open the door from now on | Whoever got into an account could make a link for themselves, use it and delete it with its history, and only its use would have shown (report item SEG-05). The page lets the owner tell whether it was them, and the email tells them without looking; it cannot be turned off, so that an intruder cannot turn it off first (the user's choice, 2026-11-04), and never carries the URL, which opens the door. Copying an existing link's URL leaves no trace, as URLs are shown again by design. `links` and `auth` publish events rather than calling `audit`, which depends on `auth`; the recorder reads the request through `RequestContextHolder`, so `links` knows nothing of IP addresses, which is why `ClientIp` moved to `config`. A sign-in's passkey is the one used last, as Spring Security does not hand the success handler the credential. Entries keep no label, which usually holds a guest's name, and no foreign keys, so that deleting a link leaves its entries. |
| Guests' IP addresses forgotten after 90 days, attempts kept 365; the browser not kept; the guest page says so | The owner sees where an attempt came from in its country, which stays; an IP address is what identifies the guest, and 90 days covers looking into a recent misuse (the user's choice, 2026-11-04). The browser was stored and never shown. The job sets the two columns to null rather than deleting, so the history keeps its rows; `app_user` may update only them, and the release before reads a null IP as one not kept. Not a legal opinion: an owner with paying guests may need one. |

## Tests

- **Owners' activity**: `OwnerActivityTests` (a sign-in with its passkey and where it came from, every change to a link and nothing for a refused one, the page with only the owner's entries, newest first).
- **History's retention**: `EventPurgeJobTests` (attempts deleted after a year, IP addresses forgotten after 90 days, browsers from earlier releases too, and that `app_user` may update nothing else of an attempt).
