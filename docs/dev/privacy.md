# Privacy and history

Read when: changing what is kept about guests or owners and for how long: attempts, IP addresses, owners' activity, purge jobs.

See also: [security](../security.md), [links](links.md), [architecture](architecture.md#jobs).

## Decisions

| Decision | Why |
|----------|-----|
| Owners' activity: sign-ins and changes to links, recorded from events on the request's thread, the link's change in its own transaction; an email, always, about a link that may open the door from now on | Whoever got into an account could make a link for themselves, use it and delete it with its history, and only its use would have shown (report item SEG-05). The page lets the owner tell whether it was them, and the email tells them without looking; it cannot be turned off, so that an intruder cannot turn it off first (the user's choice, 2026-11-04), and never carries the URL, which opens the door. Copying an existing link's URL leaves no trace, as URLs are shown again by design. `links` and `auth` publish events rather than calling `audit`, which depends on `auth`; the recorder reads the request through `RequestContextHolder`, so `links` knows nothing of IP addresses, which is why `ClientIp` moved to `config`. A sign-in's passkey is the one used last, as Spring Security does not hand the success handler the credential; a registration or a recovery names the passkey it created, the recovery marked as one. Entries keep no label, which usually holds a guest's name, and no foreign keys, so that deleting a link leaves its entries. |
| Guests' IP addresses forgotten after 90 days, attempts kept 365; the browser not kept; the guest page says so | The owner sees where an attempt came from in its country, which stays; an IP address is what identifies the guest, and 90 days covers looking into a recent misuse (the user's choice, 2026-11-04). The browser was stored and never shown, and its column went with 4.0.0 (changeset 040). The job sets the IP address to null, with the distance to the door and its accuracy, rather than deleting, so the history keeps its rows; `app_user` may update only them, and the release before reads a null IP as one not kept. Not a legal opinion: an owner with paying guests may need one. |

| An edit is emailed only if the link opens where it did not, and the email says how | Every edit sent "may open the door from now on", a corrected label too, which teaches an owner to ignore the one email meant to tell of an intruder; and its text gave only the dates, so a PIN taken off read like nothing (review item SEC-07, 2026-11-19). `AccessLinks` compares the link before and after: dates that start earlier or end later, a schedule that changed unless there was none before (telling a wider schedule from a narrower one is not worth getting wrong), a PIN removed or changed, a link locked by wrong PINs unlocked. Every edit is still recorded in the activity. |
| The guest's page says that backups keep what is deleted for up to 30 days more (`welcomehome.audit.backup-retention`, only for that text) | It said the IP address is deleted after 90 days, while the dumps keep it up to 30 more (DOC-04 of the 2026-11-19 review). The app cannot know the stack's `BACKUP_RETENTION_DAYS`, so the property repeats it, with the same default. |

## Tests

- **Owners' activity**: `OwnerActivityTests` (an edit that only renames or narrows a link recorded and not emailed, one that takes off the PIN or changes the hours saying so in its email; a sign-in with its passkey and where it came from, a registration as one, a recovery link's marked and said on the page, every change to a link and nothing for a refused one, the page with only the owner's entries, newest first).
- **History narrowed**: `AccessHistoryTests` (to what the attempts came to, from a day of the lock's calendar, the next page still narrowed, malformed values refused).
- **History's retention**: `EventPurgeJobTests` (attempts deleted after a year, IP addresses forgotten after 90 days, the distance to the door with them, and that `app_user` may update nothing else of an attempt).
