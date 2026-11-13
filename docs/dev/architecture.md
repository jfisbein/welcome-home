# Architecture

Read when: adding a class or a module, using another module's types, adding a scheduled job or a command, or looking up what a word means in the code.

See also: [database](database.md) for tables and roles, [conventions](conventions.md).

## Modules

A modular monolith: one Spring Boot app is the only component that talks to Nuki, Postgres and the secrets. Modules are the packages under `welcomehome`, checked by `ModularityTests` (Spring Modulith): a module uses another only through its public types.

| Module   | Responsibility                                                                                              |
|----------|-------------------------------------------------------------------------------------------------------------|
| `auth`   | Owners' accounts and how they sign in: security configuration, passkey sign-in, invitations and recovery links (`InvitationService`), registration from one (`OwnerRegistration`), the owner's passkeys page (`PasskeysController`), the owner's account page (`AccountController`: changing their email, closing their account), deleting an owner (`OwnerAccounts`, which publishes `OwnerDeleting`), whether the menu offers the admin panel (`NavigationModel`), `CurrentOwner`; publishes `OwnerSignedIn` on each passkey sign-in (`SignInAnnouncer`) |
| `admin`  | The admin panel and the `--bootstrap-admin` and `--recover-owner` commands, through `auth`'s `InvitationService` and `OwnerAccounts`; it depends on `auth`, never the reverse, so its controller gets `CurrentOwner` like the others |
| `preferences` | An owner's preferences (`PreferencesController`): the language of their pages and emails, in `owner`, and each browser's color theme, in the `theme` cookie (`Theme`, given to every page by `ThemeModel`); the language every page is drawn in (`OwnerLanguageResolver`) |
| `config` | `WelcomeHomeProperties`: the public URL (`BASE_URL`), checked once at startup, from which every module builds its links; `Languages`: the languages with a bundle; `TimeZoneNames`: a time zone's name in a language, which pages and emails show instead of its id; `ClientIp`, `ClientCountry`: where a request came from, a guest's or an owner's; depends on no other module |
| `crypto` | Keysets (`Keysets`, `KeysetGenerator`, `KeysetRotator`), `CryptoService` (AEAD), `LinkTokenSigner` (MAC)  |
| `db`     | `OwnerTransactions`: runs work in a transaction with `app.owner_id` set for Row-Level Security             |
| `nuki`   | The only module that talks to Nuki: `LockProvider` (interface), `NukiWebLockProvider`, `NukiWebClient` (the API's calls), `NukiRequests` (timeouts, retries, what a failure means), `NukiCodes` (Nuki's codes as types), `NukiOAuthClient` (authorizing and tokens), connections (`NukiConnections`: an API token or OAuth2 tokens, refreshed before they expire) and their daily check, their tokens encrypted with a rotated keyset's new key on start (`TokenReEncryption`), connecting with OAuth2 (`NukiAuthorizationController`), Nuki's webhooks reporting the outcome of an action (`NukiWebhookController`, `ActionReports`) |
| `locks`  | Import (`LockSync`), settings and state of each owner's locks, smart locks and Openers, a lock's entrance, what is wrong with them for the pages that list them (`LockChecks`), and whether to believe Nuki when it reports one offline (`LockReachability`, which `access` tells of each opening) |
| `links`  | Access links: create, list, edit, regenerate, revoke (`AccessLinks`, `LinkStatus`, `LinkUrls`), and the days and hours at which they open (`LinkSchedule`, `LinkSchedules`); publishes `LinkChanged` in every change's transaction |
| `access` | The guest flow: guest page, `POST /api/open` (`OpenDoor`), rate limits, per-lock mutex (`LockMutex`), unlatch confirmation, the check of a guest's distance to the door (`DoorDistance`) |
| `audit`  | Access events, history pages, the home page's latest attempts (`RecentAttempts`), both drawn from `AttemptRow`s (`AttemptRows`: time in the lock's zone, outcome and country in the reader's language), purge job; publishes `AccessAttempted`. Owners' activity: recorded from `OwnerSignedIn` and `LinkChanged` (`OwnerActivityRecorder`), shown at `/settings/activity` (`OwnerActivityController`) |
| `welcomehome` (root package, not a module) | What spans modules: the home page (`HomeController`), `/settings` and the former addresses of its sections (`SettingsController`), the error pages (`ErrorPages`) |
| `notifications` | Emails to owners (opened, failed, denied, Nuki disconnected, a lock's problems) by their preferences, and always about their account (a link created, edited or given a new URL, recovery link created, passkey added, and to the previous address when the owner changes it) and about a door opened only because a guest far away confirmed, sent after commit (`OwnerNotifier`) |

## Words

**Words.** A *lock* is a device in Nuki, a smart lock or an Opener: `NukiDevice` as Nuki reports it, `OwnerLock` as an owner keeps it. A *door* is what a guest opens, and the *entrance* the building's door that an Opener opens; both are locks to the code. Of a lock, `LockSnapshot` is all that Nuki last knew, and `LockPosition` where it stands within that. Of an action, `LockLogEntry` is its line in the lock's log, `LoggedAction` what it was, `ActionResult` how it ended, and `PendingAction` one Nuki accepted and has yet to run. `LockMutex` lets one run per lock. An *attempt* is a guest trying a link: an `AccessEvent` when recorded, an `access_event` row once stored, an `AttemptRow` when shown, and its `AccessOutcome` how it ended for the guest. An owner's pages say "Estado" for much of this: the UI's words and the bundles' keys stay as they are.

## Jobs

**Jobs** (`@Scheduled`, server time zone): Nuki connection check (08:00), trying disconnected connections again (hourly), locks' sync and the check of their problems (08:15), purge of access events and owners' activity, and of their IP addresses after 90 days (03:30), purge of links that ended over a year ago (03:32), purge of invitations expired more than 30 days ago (03:35); in production a separate `backup` container dumps the database at 04:00. On every start of the server, Nuki tokens that an earlier AEAD key encrypted are encrypted with the primary one (`TokenReEncryption`).

## Commands

**Commands**: `--bootstrap-admin` and `--recover-owner=<email>` start the app without the web server (`WelcomeHomeApplication.runsACommand`), print a link and exit. Anything web-only must stay optional without a web server (see `CommandLineTests`).
