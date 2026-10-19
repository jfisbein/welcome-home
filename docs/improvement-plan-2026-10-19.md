# Improvement plan, 2026-10-19

Built from the code review in [review-2026-10-19.md](review-2026-10-19.md). Every conclusion in that review was checked against the code at `0b09dc5` (v1.4.0) and, for the Nuki-related ones, against Nuki's Swagger definition. The first section records what held, what had to be corrected and what was dropped; the plan uses only what held, plus one addition that is not in the review: making the app multilingual (phase 4).

## What the check found

| Review item | Verdict | Notes |
|-------------|---------|-------|
| 1. `OwnerTransactions` joins the outer transaction | Correct, latent | No `@Transactional` method calls it today (the only two, `OwnerRegistration.finish` and `OwnerDao.delete`, do not). The suggested `REQUIRES_NEW` is the wrong fix: it would split the atomicity of callers that rely on joining. A guard on entry is right. |
| 2. Read timeout on the unlatch becomes `nuki-error` | Correct, and wider | A read timeout and a 5xx on the action POST both end as `Reason.UNAVAILABLE`, then `NUKI_ERROR`, and `open.js` re-enables the button with "Inténtalo de nuevo". Also missed by the review: when the guest's own `fetch` fails (mobile network drops during the 15 s wait), `open.js` falls back to `nuki-error` too. |
| 3. Admin can sign in as any owner via a recovery link | Correct | No email exists today for recovery links or new passkeys. |
| 4. Outcome codes as strings in four places | Correct, with a correction | The cited `UNAVAILABLE` inconsistency is deliberate (`NotificationKind` documents that "unavailable" is a failure the owner must act on). The review missed a real one: `not-confirmed` is `AccessResult.OK` and "Abierta sin confirmar" in history, but its email says "no ha podido abrir la puerta". |
| 5. Two `UNAVAILABLE`s with opposite meanings | Correct | Merged into 4 below. |
| 6. Connecting verifies only the read scope | Correct, effects misstated | `GET /smartlock/log` (account-wide, no lock id needed) exists. Without `smartlock.log` the door **does** open and the guest gets `not-confirmed` after 15 s, not "unavailable". Without `smartlock.action` the guest gets "unavailable", and the owner is emailed with the wrong cause ("inactiva o Nuki está desconectado"), so "the owner never sees it" is overstated. |
| 7. `Thread.sleep` on request threads | Correct, low impact | Rate limits (10/min per IP, 5/min per link) and a handful of owners keep it far from exhausting 200 Tomcat threads. Virtual threads are cheap but optional. |
| 8. `MaxRAMPercentage=75` with no memory limit | Correct | The `app` service in `deploy/compose.yaml` has no limit; the heap is sized against what the LXC reports, shared with Postgres and other stacks. |
| 9. No injectable `Clock` | Partly correct | Ten `now()` calls, yes. But `LinkStatus.of` and `LinkForm.validate` already take `now` as a parameter, so their tests are already deterministic, and a `Clock` would not reach SQL `now()` in `InvitationDao` or `LinkDao`. Low value. |
| 10. `BASE_URL` parsed in four places | Correct | Only `InvitationService` rejects a blank value. |
| `access_event.nuki_response` never written | Correct | |
| `lock.connection_id` write-only | Correct | Only the foreign key's `ON DELETE SET NULL` touches it after insert. Harmless. |
| `lock` and `owner` are Postgres keywords | Rejected | Both are non-reserved and work; renaming tables is churn with no benefit for a Postgres-only app. |
| No admin promotion or demotion | Correct | A functional gap, not needed with a few known owners. |
| Expired invitations never purged | Correct, low value | `invitation` holds no email, only a token hash and an optional note: housekeeping, not GDPR. |
| History cursor on `occurred_at` alone | Correct in theory | Two events in the same microsecond at a page boundary. Cheap to fix, near-impossible to hit. |
| `last_checked_at::timestamp` depends on the session time zone | Correct, low value | Production sets `TZ=Europe/Madrid`, so it shows server time consistently today. |
| Nine shapes of "link" | Rejected | They are per-query projections with different columns and types (UTC versus zoned), and `ResolvedLink`/`GuestLink` live in `access`, which must not reach into `links`. One canonical record would need nullable fields or break module boundaries. |
| Names (`LinksController`, `LockActions`, `OwnerAccountDao`/`OwnerDao`, `CryptoService`) | Partly | `LinksController` versus `LinkController` is worth fixing. The others are taste; the two owner DAOs sit in two modules on purpose and have different methods (`findByEmail`, `findIdByEmail`). |
| Modules isolated in Java, not in SQL | Correct | Documenting table ownership is cheap; routing every read through public APIs is not worth it. |
| Spanish UI strings in Java | Correct, and extended | Not only Java: templates and JavaScript hold the UI text too. Phase 4 takes all of it out to message bundles. |
| Daily check could also sync locks | Correct | Feature idea. |
| Filter the confirmation by `trigger` | Plausible | The log has `trigger` (4 = web, 5 = app), so an unlatch from the Nuki app can be told apart. Needs one read-only look at a real log entry before relying on it. |
| CI compiles twice, Gradle cache not kept | Correct | The `--mount=type=cache` contents are never exported to the GHA cache, so every image build downloads dependencies again. |
| `HomeController` javadoc stale | Correct | |
| Guest GET creates a session for CSRF | Correct, fixed by 1.4 | Not worth changing for its own sake, but the token kept in that session is what goes stale (next row); fixing that removes the session too. |
| Not in the review: the guest page's CSRF token goes stale | Found while checking | The token lives in the session, which expires after 30 minutes: a guest who presses "Abrir" later, on a page the phone kept open, gets a 403 on every try until reloading it. Task 1.4. |

## The plan

Each task is one commit, with `./gradlew build` green and a hand mutation test (break the code, see a test fail, restore it), as AGENTS.md asks. Phases are releases.

### Phase 1, v1.5.0: the door and what people are told about it

**1.1 An unlatch with no answer is confirmed from the log, never retried blindly.** (review 2, 5)

- In `nuki/LockProviderException.Reason`, rename `UNAVAILABLE` to `NUKI_ERROR`, the `OpenResult` it becomes (for an action, Nuki did not run it), and add `NO_ANSWER` (the request was sent and the outcome is unknown).
- In `nuki/NukiWebClient.call`, for a call that is not safe to repeat, throw `NO_ANSWER` on a read timeout (a `ResourceAccessException` that is not a connection failure) and on a 5xx. Connection failures, an exhausted 429 and other 4xx stay `NUKI_ERROR`.
- In `access/OpenDoor.unlatchAndConfirm`, on `NO_ANSWER` go on to `UnlatchConfirmation.await`, which answers `OPENED`, `LOCK_FAILED`, `NIGHT_MODE` or `NOT_CONFIRMED`.
- In `static/js/open.js`, a failed `fetch` or an answer that is not JSON shows the `not-confirmed` message ("empuja la puerta; si no abre, inténtalo de nuevo") instead of `nuki-error`.
- Tests: `NukiWebClientTests` for read timeout and 5xx on the action (one request, `NO_ANSWER`); `OpenDoorTests` for an action POST that times out while the log shows the unlatch (`OPENED`) and while it does not (`NOT_CONFIRMED`).

**1.2 One enum for outcomes.** (review 4, 5, and the Spanish strings)

- A public `audit.AccessOutcome` enum with the current codes (`opened`, `not-confirmed`, …) and its `AccessResult`. It replaces `access.OpenResult`, whose HTTP statuses become an exhaustive switch in `OpenController`; `AccessEvent` and `AccessAttempted` carry the enum instead of a `String`. The column stays text with the same codes, so no migration.
- `AccessHistoryController` and `OwnerNotifier` switch exhaustively over the enum. Labels and the email texts this task rewrites go to `i18n/messages.properties`, which this task creates. From here on every new user-facing text goes into that bundle, so the later phases add no Spanish strings that phase 4 would have to move again.
- Fix the `not-confirmed` email: subject and body say that the guest asked to open and the lock did not confirm it, not that the guest could not open.
- Tests: the existing history and notification tests, plus one for the `not-confirmed` email text.

**1.3 Missing Nuki permissions are caught when connecting and named when opening.** (review 6, corrected)

- `nuki/NukiConnections.connect` also calls `GET /smartlock/log?limit=1`; a 403 rejects the token with a message on `nuki/connection.html` that names the missing "registro" permission.
- A `missing-permission` outcome for a 403 on the unlatch, shown in history and in the failure email as "el API token no tiene permiso para abrir", instead of "unavailable".
- In `UnlatchConfirmation.await`, any failure to read the log other than `NUKI_ERROR` (a missing permission, a revoked token) ends the wait at once instead of polling for 15 s.
- Tests: WireMock 403 on the log at connect, 403 on the action at open, 403 on the log during confirmation.

**1.4 A guest page left open for hours still opens.** (found while checking the review)

- `POST /api/open` needs no CSRF token: the link's token in the body is the guest's only credential, and a forged request would need it anyway. Owners' forms keep theirs.
- Guest pages keep no session: they render no CSRF token (`sessionless` in the model, read by the layout), and `open.js` sends none.
- Tests: an opening with no session and no CSRF token opens, and creates no session; guest pages, valid or not, create no session and carry no token, while `/login` keeps its own.

### Phase 2, v1.6.0: hardening

**2.1 `OwnerTransactions` refuses to switch owner inside a transaction.** (review 1) On entry, read `current_setting('app.owner_id', true)`; if it is set and differs from `ownerId`, throw `IllegalStateException`. Keep `REQUIRED`. Test in `OwnerTransactionsTests`: two owners inside one outer `TransactionTemplate` fail, the same owner twice works.

**2.2 Account-security emails.** (review 3) Email the owner when an admin creates a recovery link for them, when one is redeemed, and when a passkey is added from `/passkeys`. These are always sent, not subject to preferences. They go to an unverified address today, so this pairs with the "verify the owner's email" idea in AGENTS.md. Tests in `NotificationTests` and `PasskeyRecoveryTests`.

**2.3 A memory limit for the app.** (review 8) First measure on `docker.fritz.box`: the LXC's RAM (`free -m`) and the app's resident memory (`docker stats`). Then set `mem_limit` on `app` in `deploy/compose.yaml` with headroom over that, keeping `MaxRAMPercentage=75`. Try it on a local copy of the stack, then follow the runbook for the upgrade. Done with a local copy of the stack running v1.5.0, as this machine has no SSH access to the server: 1 GiB, which leaves the JVM's 250 to 330 MB outside the heap room beside a 768 MB heap. The LXC's free memory is still to be checked before deploying.

**2.4 Virtual threads (optional).** (review 7) `spring.threads.virtual.enabled: true` in `application.yaml`. It also moves `@Async` and `@Scheduled` to virtual threads; run the whole suite and watch `NotificationTests` and `LockActionsTests`.

**2.5 The health check does not depend on SMTP (proposed, not done).** (found while checking 2.3) With `spring.mail.host` set, Spring Boot adds the mail server to `/actuator/health`, so when SMTP2GO cannot be reached the app reports `DOWN` and its container becomes unhealthy. `cloudflared` starts only once `app` is healthy, so a stack started during an SMTP outage stays offline, although emails are optional and dropped when they fail. Confirmed on the local copy of the stack, whose SMTP credentials are not real. Fix: `management.health.mail.enabled: false`, with a test that the health is `UP` while the SMTP server is unreachable.

### Phase 3, v1.7.0: maintainability

**3.1 One `welcomehome.base-url` property.** (review 10) A `@ConfigurationProperties("welcomehome")` record with a validated `URI baseUrl`, injected into `InvitationService`, `LinkUrls`, `OwnerNotifier` and `SecurityConfiguration`. Check `CommandLineTests`, since the commands start without a web server.

**3.2 Schema cleanup.**

- Drop `access_event.nuki_response`.
- Drop `lock.connection_id` and its foreign key (optional: harmless as it is).
- Grant `DELETE` on `invitation` to `app_user` and purge invitations expired more than 30 days ago in the existing purge job.
- Page the history on `(occurred_at, id)`.

**3.3 Document which module owns each table** in AGENTS.md, and which modules read which tables in SQL. (review "isolated in Java, not in SQL")

**3.4 Small fixes.** Rename `LinksController` to `LockLinksController`; update the `HomeController` javadoc.

**3.5 CI builds the jar once.** (review "CI compiles twice") Have the image copy the jar that `./gradlew build` already produced (`COPY build/libs/*.jar`), and note in the README that a local `docker build` needs `./gradlew bootJar` first. Check that the PR, `main` and tag workflows still produce the same tags.

### Phase 4, v1.8.0: multiple languages

No user-facing text in Java, templates or JavaScript: it all lives in message bundles, Spanish is the default, and adding a language means adding one file. Measured at `0b09dc5`, there is user-facing text in:

| Where | What |
|-------|------|
| 19 Thymeleaf templates | about 200 lines of text, `lang="es"` hard-coded, 12 date patterns such as `d/M/yyyy HH:mm` |
| 8 JavaScript files | about 30 strings, 12 of them the guest page messages in `open.js` |
| 3 Java classes | `OwnerNotifier` (every email), `AccessHistoryController` (outcome labels), `NotificationsController` (the test email) |

Command-line output, log lines and exception messages are for the operator, not the UI: they stay in English in the code.

**4.1 Infrastructure.**

- `spring.messages.basename: i18n/messages`, `fallback-to-system-locale: false` (otherwise the server's locale decides which bundle wins), and `always-use-message-format: true`, so that an apostrophe is always written `''`, with arguments or without. That rule matters for English, where `can't` with a single quote silently drops text.
- A `LocaleResolver` in `auth`: a signed-in owner's own language (4.4), else the browser's `Accept-Language` among the supported languages, else Spanish. Guests always get their browser's language.
- `th:lang="${#locale.language}"` in the layout instead of `lang="es"`, for screen readers and hyphenation.
- Tests: every key used in a template (`#{...}`) or in Java exists in the default bundle; every bundle has exactly the default bundle's keys; rendering each page answers HTML with no `??` (Thymeleaf's mark for a missing key).

**4.2 Templates.** Every text to `#{key}`, one commit per area: guest pages; sign-in, invitation and passkeys; locks and links; Nuki connection, notifications and history; admin. Keys are named by page and purpose (`links.revoke.confirm`), not by the Spanish text. Date patterns go into the bundle (`format.datetime`), so each language sets its own; any count uses `ChoiceFormat` for plurals. The existing tests assert Spanish text and must keep passing unchanged, which shows nothing was lost on the way.

**4.3 JavaScript.** The CSP (`script-src 'self'`) rules out inline scripts, so the template renders each script's texts as `data-*` attributes from the bundle, and the script reads them from `dataset`. `open.js` takes its message per outcome code this way.

**4.4 Emails in the owner's language.** Emails are sent after commit on another thread, with no request to take a language from, so the owner's language is stored.

- A changeset adds `owner.locale` (text, not null, default `es`) and `GRANT UPDATE (locale) ON owner TO app_user`, a column grant so that `app_user` still cannot change `email` or `is_admin`.
- Registration stores the language of the registering request; the owner changes it on `/notifications` (or a new account page).
- `OwnerNotifier`, the test email and the account-security emails of 2.2 render with the owner's locale through `MessageSource`.
- Tests in `NotificationTests`: an owner with `en` gets the English email once 4.6 exists; until then, a bundle in the test resources stands in.

**4.5 A guard test against literal text.** Like the route guard in `OwnerIsolationTests`: parse every template with jsoup (a new test dependency) and fail on an element with its own text but no `th:text` or `th:utext`, and on `placeholder`, `title`, `aria-label` or `alt` without its `th:` version. Without it, the next page added in Spanish goes unnoticed.

**4.6 English as the second language.** `i18n/messages_en.properties`, added to the supported languages. It is what proves the rest works; tests: the guest page with `Accept-Language: en`, an owner page for an owner with `locale = en`.

**4.7 Documentation.** AGENTS.md, under Conventions: "UI texts in `i18n/messages*.properties`, Spanish the default; no user-facing text in Java, templates or JavaScript". The README gets "Adding a language": copy the bundle, translate it, add the language to the supported list.

### Backlog

Valid, but not worth doing now:

- A language per link, chosen by the owner, for a guest whose phone is set to a language other than the one they read.

- An injectable `Clock` (review 9), if a test ever flakes on the wall clock.
- Promoting and demoting admins.
- The daily connection check also syncing locks.
- Filtering the confirmation by `trigger = 4` (web), after reading one real log entry with a read-only call.
- `last_checked_at` and the passkey dates as `OffsetDateTime`.

### Not doing

- Renaming the `lock` and `owner` tables.
- Merging the link records into one.
- Renaming `LockActions` and `CryptoService`, or merging the two owner DAOs.
- `REQUIRES_NEW` in `OwnerTransactions`.
- Routing cross-module SQL reads through public APIs.
