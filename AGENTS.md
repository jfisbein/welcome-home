# AGENTS.md

Context for whoever maintains welcome-home next, person or agent. The [README](README.md) describes each feature in detail and [docs/runbook.md](docs/runbook.md) covers production operations; this file is the map: what the app is for, how it is built and why, how it is tested, and where it could go next.

## What it is

A private web app where a few owners connect their Nuki account and create date-bound links that open their door: a guest opens the link on their phone and slides to open, with no app, account or code.

**Roles**

- **Owner**: connects Nuki with an API token, imports locks, creates, edits, regenerates and revokes links, reads each lock's history.
- **Admin**: an owner who can also invite owners, list them, create passkey recovery links and delete owners. Admins never see other owners' locks, links or history.
- **Guest**: no account; has only a link.

**In production**: a Dockge stack on `docker.fritz.box` (an LXC), reached only through its own Cloudflare Tunnel. Its public URL is not in this repository: `https://welcome-home.example.com` stands for it. Image `ghcr.io/jfisbein/welcome-home`, at the major version's tag (`:v1`).

## Requirements

Functional:

- Registration only by single-use invitation (72 h); sign-in only with passkeys, no passwords. The first admin comes from a command line invitation (`--bootstrap-admin`).
- An owner pastes a Nuki Web API token; it is checked against Nuki before it is stored, encrypted. A 401 from Nuki marks the connection `DISCONNECTED`, and a daily job checks every connection.
- Locks are imported from Nuki; each has a name, a time zone (from Nuki where possible) and can be deactivated when it leaves the Nuki account.
- A link has a label and a validity window entered in the lock's time zone and stored in UTC. Its state (pending, active, expired, revoked) is computed, never stored.
- A guest's GET never acts on the lock: only a POST after sliding to open unlatches (link previews in WhatsApp, Telegram or Slack make GETs). The guest learns whether the door opened, failed, or was refused in night mode.
- Every attempt with a valid link is recorded with IP address and browser; attempts are purged after a retention (365 days).
- Owners who lose their passkey get a recovery link (admin panel or `--recover-owner`); they keep their other passkeys.
- Owners get emails about their locks (opened, failed, denied, Nuki disconnected) and choose which ones, once they have verified their address with the link sent when they register.
- Spanish and English: a guest's pages in their browser's language, an owner's pages and emails in the language they chose.

Non-functional:

- Owners are isolated from each other in the database itself (Row-Level Security), not only in code.
- Secrets at rest: the Nuki API token is encrypted (AEAD, bound to its owner); links store no token at all (HMAC).
- Never two unlatches for one press: one action per lock at a time, and the unlatch is never retried once Nuki may have run it.
- Single instance: rate limits, the per-lock mutex and scheduled jobs live in memory in one process.
- GDPR: guests' IP addresses expire; a deleted owner's data is gone from backups after their 30-day retention.
- Upgrades within a major version are pulling the new image: no backup, no change on the server, and rolling back is pinning the previous release.

Out of scope so far: OAuth2 with Nuki, geofencing, push notifications, use limits, recurring schedules, Nuki Opener, native apps.

## Stack

| Piece                | Choice                                                                             |
|----------------------|------------------------------------------------------------------------------------|
| Language, build      | Java 25, Gradle 9 (Kotlin DSL, wrapper)                                             |
| Framework            | Spring Boot 4.1, Spring Security 7.1 (WebAuthn passkeys), Spring Modulith 2.1       |
| Web                  | Spring MVC, Thymeleaf, Pico CSS, a few small vanilla JS files (no build step)        |
| Data                 | PostgreSQL 18, `JdbcClient` with records, Liquibase (XML changelogs)                |
| Crypto               | Google Tink: AEAD AES-256-GCM (Nuki tokens), MAC HMAC-SHA256/128 (links)            |
| Nuki                 | Nuki Web API over `RestClient`, with timeouts and backoff                           |
| Rate limiting, cache | Bucket4j and Caffeine, in memory                                                    |
| Static analysis      | Error Prone + NullAway (JSpecify mode), javadoc doclint as errors                   |
| Email                | Spring Mail over SMTP (SMTP2GO in production)                                       |
| Tests                | JUnit 5, AssertJ, Testcontainers (Postgres), WireMock (Nuki), GreenMail (SMTP), webauthn4j-test |
| Runtime              | Docker (JRE 25, non-root), Docker Compose in Dockge, cloudflared, GHCR, GitHub Actions |

## Architecture

A modular monolith: one Spring Boot app is the only component that talks to Nuki, Postgres and the secrets. Modules are the packages under `welcomehome`, checked by `ModularityTests` (Spring Modulith): a module uses another only through its public types.

| Module   | Responsibility                                                                                              |
|----------|-------------------------------------------------------------------------------------------------------------|
| `auth`   | Security configuration, passkey sign-in, registration from an invitation or recovery link (`OwnerRegistration`), the owner's passkeys page (`PasskeysController`), `CurrentOwner` |
| `admin`  | Invitations and recovery links (`InvitationService`), the admin panel, the `--bootstrap-admin` and `--recover-owner` commands |
| `config` | `WelcomeHomeProperties`: the public URL (`BASE_URL`), checked once at startup, from which every module builds its links; `Languages`: the languages with a bundle; depends on no other module |
| `crypto` | Keysets (`Keysets`, `KeysetGenerator`, `KeysetRotator`), `CryptoService` (AEAD), `LinkTokenSigner` (MAC)  |
| `db`     | `OwnerTransactions`: runs work in a transaction with `app.owner_id` set for Row-Level Security             |
| `nuki`   | The only module that talks to Nuki: `LockProvider` (interface), `NukiWebLockProvider`, `NukiWebClient`, connections and their daily check |
| `locks`  | Import (`LockSync`), settings and state of each owner's locks                                               |
| `links`  | Access links: create, list, edit, regenerate, revoke (`AccessLinks`, `LinkStatus`, `LinkUrls`)            |
| `access` | The guest flow: guest page, `POST /api/open` (`OpenDoor`), rate limits, client IP, per-lock mutex (`LockActions`), unlatch confirmation |
| `audit`  | Access events, history pages, the home page's latest attempts (`RecentAttempts`), purge job; publishes `AccessAttempted` |
| `notifications` | Emails to owners (opened, failed, denied, Nuki disconnected) by their preferences, and always about their account (recovery link created, passkey added), sent after commit (`OwnerNotifier`) |

**Guest flow** (`access.OpenDoor`): rate limit per IP → the link exists, its MAC is valid and it is not revoked (`resolve_link`, a `SECURITY DEFINER` function, is the only way to read a link without an owner context) → rate limit per link → within its dates → lock active → owner's Nuki connection OK → in the owner's context, unlatch under the lock's mutex (a second guest waits and gets the same result) → read the lock's log until an unlatch shows up with trigger 0 or 4 (not by hand, button or accessory), within 15 s → record the event.

**`LockProvider`** (`listLocks`, `getState`, `unlatch`, `getRecentLog`, all by owner id) isolates Nuki: tokens are decrypted inside `nuki` and never leave it. Another way to reach a lock (Nuki Bridge, MQTT, OAuth2) is another implementation or a change inside `nuki`, without touching the rest.

**Data model** (`src/main/resources/db/changelog/changes/`): `owner` (`language`, and the email's verification: `email_verified_at`, the link's hash and expiry; the only columns `app_user` may update), `invitation` (also recovery links: `recovers_owner_id`), `nuki_connection`, `lock`, `access_link` (`token_version`), `access_event` (`ip`, `country`), `notification_preference`, and Spring Security's `user_entities` and `user_credentials`. Every table with `owner_id` has Row-Level Security with a policy that fails closed (`owner_id = nullif(current_setting('app.owner_id', true), '')::uuid`). `owner` and `invitation` have no `owner_id` and no RLS: only `auth` and `admin` use them. Neither do the passkey tables, keyed by the owner's email: `auth` joins `user_entities` by email in every query.

**Tables by module.** Spring Modulith checks only Java dependencies; SQL crosses modules in the places below, all reads for display or a daily job, except `admin` deleting an owner. Anything else goes through the owning module's public types.

| Table | Owner | Also used in SQL by |
|-------|-------|---------------------|
| `owner` | `auth` (registration inserts it) | `admin`: lists owners and deletes one; `audit`, `nuki`: owner ids for their daily jobs; `notifications`: the owner's email |
| `user_entities`, `user_credentials` | `auth` (Spring Security's passkey store) | `admin`: deletes an owner's passkeys |
| `invitation` | `admin` | none (`auth` goes through `InvitationService`) |
| `nuki_connection` | `nuki` | none |
| `lock` | `locks` | `links`: lock names in link lists; `notifications`: name and time zone; `resolve_link` |
| `access_link` | `links` | `audit`: labels in the history; `notifications`: labels; `access`: only through `resolve_link` |
| `access_event` | `audit` | none |
| `notification_preference` | `notifications` | none |

**Roles in Postgres**: `migrator` owns the schema and runs Liquibase; the app connects as `app_user`, with no ownership and no `BYPASSRLS`. No `FORCE ROW LEVEL SECURITY`, so that `resolve_link` (owned by `migrator`) sees the link. Roles come from `deploy/db-init/01-create-roles.sh`, used by development, tests and production alike.

**Jobs** (`@Scheduled`, server time zone): Nuki connection check (08:00), access event purge (03:30), purge of invitations expired more than 30 days ago (03:35); in production a separate `backup` container dumps the database at 04:00.

**Commands**: `--bootstrap-admin` and `--recover-owner=<email>` start the app without the web server (`WelcomeHomeApplication.runsACommand`), print a link and exit. Anything web-only must stay optional without a web server (see `CommandLineTests`).

## Decisions and why

| Decision | Why |
|----------|-----|
| Nuki Web API token pasted by each owner, not OAuth2 | OAuth2 needs a client secret from Nuki; the token works today. OAuth2 is the planned v2 (below). |
| Unlatch (action 3), confirmed from the lock's log | Nuki runs actions asynchronously: a 204 only means accepted. |
| The unlatch is retried only on 429 or when no connection was made | Retrying after Nuki may have run it could open the door twice. |
| Passkeys only, invitations only | No passwords to leak; a private app with few, known users. |
| Links are `{link_id}.{HMAC(link_id, token_version)}` | Nothing to steal from the database; a URL can be shown again; incrementing `token_version` invalidates every earlier URL. |
| Row-Level Security plus `owner_id` in every query | Isolation does not depend on every query being right. `OwnerIsolationTests` checks every route with an id. |
| AEAD with the owner id as associated data | A ciphertext copied to another owner's row does not decrypt. |
| Admins see only emails and dates | An admin is still just an owner to RLS. Recovery links are the exception the user chose: whoever holds one signs in as that owner, so the admin who creates one could. The owner is emailed when one is created and when a passkey is added, so its use is not hidden from them. |
| Recovery adds a passkey and keeps the old ones | The user's choice; the owner then deletes the lost device's passkey at `/settings/passkeys`, which never deletes the last one. |
| `CF-Connecting-IP` for the guest's IP, `forward-headers-strategy=framework` | The tunnel is the only way in, so Cloudflare's headers are trustworthy; `X-Forwarded-For` starts with whatever the client sent. |
| `/o/{token}` masked in logs, `Referrer-Policy: no-referrer`, `noindex` | Guest URLs are bearer credentials. |
| No CSRF token on `POST /api/open`, and no session for guests | The link's token in the body is the credential, which a forged request would need anyway; a CSRF token kept in the session went stale when a guest pressed "Abrir" more than 30 minutes after opening the page. |
| No ports published; own cloudflared in the stack | With a published port the app was reachable from the LAN and the forwarded headers could be spoofed. |
| Database passwords as files (`configtree:`), keysets as Docker secrets | No secrets in environment variables or in the image. |
| Keysets backed up apart from the dumps (Vaultwarden) | A stolen dump alone does not expose the Nuki tokens. |
| Releases are `vX.Y.Z` tags; production follows the major version's tag (`:v1`) | Upgrading is Dockge's Update, with no backup and no change on the server. The running release is the image's `org.opencontainers.image.version` label; rolling back is pinning the previous one. It holds because releases within a major are backward compatible (Conventions). |
| Emails are sent after commit, on another thread, and dropped if they fail | A guest never waits for SMTP, and a mail problem never fails an opening. At most one email about refused attempts per link per hour. |
| The color theme in a cookie, not in the owner's row | The user's choice: it belongs to the browser, so a phone and a laptop may differ, and it also applies to the pages before signing in. The server writes `<html data-theme>`, so no script and no flash of the other theme; no cookie means the system's. |
| Pico CSS 2.1.1 kept, though Pico is unmaintained since 2.1.1 (repositories archived) | It is a static CSS file served from `static/css/` with no JavaScript, so there are no fixes to miss, and what it lacks goes in `app.css`. The templates lean on its classless styling of semantic HTML (`article`, `nav`, `fieldset role="group"`, `aria-busy`, `aria-current`, automatic dark mode): Bootstrap, Bulma or Pure would mean adding classes to every template for no visible gain, and Pure is barely maintained either. Revisit if a browser starts drawing something wrong that `app.css` cannot fix cheaply, or for a redesign; then prefer a fork of Pico, or own CSS keeping its variables. |

## Conventions

- **Language**: code, comments, javadoc, docs and commit messages in English. Table, column and enum names in English. UI texts only in `i18n/messages*.properties` (Spanish the default bundle, English the second): never in Java, templates or JavaScript, which `TemplateTextTests` checks. A message with markup goes through `th:utext`, with every value escaped by `#strings.escapeXml`; an apostrophe in a message is written `''`. A new language: the README's "Adding a language".
- **Javadoc** on every public class and method (`-Xdoclint:all -Werror` fails `check`). Comment the why, sparingly.
- **Nullness**: everything is non-null unless `@Nullable` (JSpecify); every package has `@NullMarked`; NullAway fails the build. Spring 7's `JdbcClient.query(X.class).list()` returns `List<@Nullable X>`: use `new SimplePropertyRowMapper<>(X.class)` or a lambda for lists.
- **Compatibility**: within a major version every release is backward compatible, as production pulls `:vN` without reading release notes and rolls back by pinning the previous release. So: the previous release runs against the new schema; no new required property, secret or environment variable (a new one has a default); nothing to change in `deploy/`; every URL already sent keeps working (guest links, invitations, recovery and verification links, history URLs). Migrations only expand the schema: new tables, nullable columns or columns with a default, indexes, grants; never drop, rename or retype, never add NOT NULL or UNIQUE to what exists, never revoke, every changeset in a transaction. `MigrationCompatibilityTests` checks the changesets from `COMPATIBLE_FROM` on. Anything else waits for the next major, which moves `COMPATIBLE_FROM` past its breaking changesets and production to `:vN+1`.
- **Liquibase**: XML with native tags; `<sql>` only for CHECK constraints, RLS, GRANTs and functions. A new table with `owner_id` gets `ENABLE ROW LEVEL SECURITY`, its policy and its grants to `app_user` in the same changeset (`MigrationTests` fails otherwise).
- **Data access**: `JdbcClient` with records; owner data only inside `OwnerTransactions.execute/run`, with `owner_id` in the query as well. No `findById(id)` without an owner.
- **Configuration**: `application.yaml` (and `application-dev.yaml`); `@ConfigurationProperties` records; durations in ISO-8601 (`PT1M`).
- **Web**: Thymeleaf templates, Pico CSS classes; every signed-in page starts with `fragments/layout :: menu('<section>')` (home, locks, settings or admin), and configuration's pages add `settings('<section>')`; CSRF on every form, HTML forms send PUT/PATCH/DELETE through `_method`. JS in `static/js/`, one small file per behavior, no inline scripts (CSP `script-src 'self'`); a script's texts come from its page, as hidden lists or `data-*` attributes. Routes taking an id must be covered by `OwnerIsolationTests` (a guard test lists them).
- **Commits**: one per task, pushed to `main` once `./gradlew build` is green.

## Testing strategy

`./gradlew build` compiles (Error Prone, NullAway), runs every test and checks javadoc. Most tests are integration tests (`@IntegrationTest`): a real Postgres in Testcontainers, initialised with the production role script, migrated by Liquibase as `migrator`, used by the app as `app_user`, with freshly generated keysets.

- **Nuki**: never the real API in tests. `NukiStandInConfiguration` starts WireMock in place of `api.nuki.io`; `NukiWebClientTests` covers status codes, timeouts, retries and backoff.
- **Passkeys**: `PasskeyEmulator` (webauthn4j-test) answers registration and sign-in like a browser with an authenticator, so the real WebAuthn endpoints are exercised end to end.
- **Isolation**: `OwnerIsolationTests` (every id route answers 404 for another owner and changes nothing), `MigrationTests` (every `owner_id` table has RLS and its policy), `RowLevelSecurityTests` (what `app_user` sees with and without an owner context), `ModularityTests` (module boundaries), `AdminPanelTests` (admins see no locks or links).
- **Guest flow**: `OpenDoorTests` (order of checks, results, confirmation, concurrency, client IP), `LockActionsTests` (one action per lock), `TokenLoggingTests` (tokens never logged), `GuestPageTests` (a GET never calls the lock provider).
- **Commands**: `CommandLineTests` starts the app without the web server.
- **Emails**: `MailStandInConfiguration` starts GreenMail as the SMTP server; `NotificationTests` checks each kind of email, preferences, the per-link quiet period, the owner's language and that two simultaneous rejections send one email; `AccountSecurityEmailTests` the recovery and passkey emails; `EmailVerificationTests` the link that verifies an owner's address, and that nothing else goes to it before.
- **Languages**: `MessageBundlesTests` (bundles have the same keys, templates name only existing ones), `PagesInEveryLanguageTests` (every page in every language, no missing text), `TemplateTextTests` (no text in templates, escaped `th:utext`), `OwnerLanguageTests` (an owner's language, and that `app_user` may update no other column of `owner`).
- **Practice**: each change is checked with a mutation test by hand (break the code, see a test fail, restore it), and deployment changes are tried on a local copy of the production stack (`COMPOSE_PROJECT_NAME=...` to stay apart from the development database).

Never call Nuki's unlatch for real outside a guest sliding to open: it opens a real door. Development uses a real Nuki token only for read-only calls.

## Build, run, release

```sh
./gradlew build                   # everything CI runs
./gradlew generateKeysets         # development keysets in secrets/ (git-ignored)
docker compose up -d              # development Postgres on localhost:5432
./gradlew bootRun                 # the app on http://localhost:8080 with the dev profile
./gradlew rotateAeadKeyset --args=<file>
```

`.github/workflows/image.yml`: pull requests build, test and build the image without pushing; pushes to `main` publish `edge`; tags `vX.Y.Z` publish `vX.Y.Z`, `vX` and `latest` (prereleases only their own tag), and then create the GitHub release.

Release: a pull request named `Version X.Y.Z` from branch `release-X.Y.Z` that bumps `version` in `build.gradle.kts`; once merged, tag the merge commit `vX.Y.Z` and push the tag. Its description becomes the GitHub release's notes, so it is written for whoever deploys: what changed since the last release, and anything to do before deploying. The workflow drops its first paragraph when it starts with "Sets the project's version", and Claude Code's attribution (`.github/scripts/release_notes.py`). Production follows `:v1`, so `deploy/compose.yaml` changes only for a new major version. Upgrading production and everything else on the server is in the runbook.

## Where things are

| Path | What |
|------|------|
| `src/main/java/welcomehome/<module>/` | the modules above |
| `src/main/resources/db/changelog/` | Liquibase changelogs |
| `src/main/resources/templates/`, `static/` | Thymeleaf pages, CSS and JS |
| `deploy/` | production stack: `compose.yaml`, `db-init/`, `backup/`, `restore.sh` |
| `compose.yaml` | development Postgres |
| `docs/runbook.md` | production operations |
| `README.md` | each feature in detail, configuration properties |

## Next steps and ideas

**OAuth2 with Nuki (v2).** Owners would authorize welcome-home in Nuki instead of pasting a token.

- Nuki approved welcome-home's Advanced API (2026-10-25): its tab in Nuki Web shows the client id and secret and lists the callback URLs, `https://welcome-home.example.com/nuki/callback,http://localhost:8080/nuki/callback`. Production keeps them in the stack's `secrets/nuki-oauth-client-id` and `secrets/nuki-oauth-client-secret`; development, in the same names under `secrets/`. Follow Nuki's current guide (docs.nuki.io/guide/authorization/oauth2): older guides and forum posts describe outdated endpoints.
- Checked by hand on 2026-10-25, with read-only scopes: `GET https://api.nuki.io/oauth/authorize` (`response_type=code`, `client_id`, `redirect_uri`, `scope` space-separated, `state`, which comes back whole) redirects to the callback with `code`, `scope` and `state`; `POST https://api.nuki.io/oauth/token` (form `grant_type=authorization_code`, `code`, `redirect_uri`, `client_id`, `client_secret`, plus HTTP Basic with the same id and secret) answers `token_type` `bearer`, `expires_in` 3599, `scope`, `access_token` and, with `offline_access`, `refresh_token`; `GET /smartlock` works with the bearer token; `grant_type=refresh_token` answers new access and refresh tokens. The refresh token rotates on every refresh, so keep only the latest; whether the previous one still works was not checked, so assume it does not. Refresh tokens last 90 days.
- Request only the scopes the app uses: `smartlock.readOnly` (or `smartlock`), `smartlock.action`, `smartlock.log`, and `offline_access` for the refresh token. `smartlock.action` was not part of the check, on purpose: it opens a real door.
- Flow: `GET /settings/nuki/oauth` redirects to Nuki with a `state` bound to the session; `GET /nuki/callback` checks `state`, exchanges the code, checks the token with `GET /smartlock`, and stores it.
- Storage: extend `nuki_connection` with the kind of credential and the encrypted access and refresh tokens plus the access token's expiry (same AEAD and associated data as today). Spring Security's OAuth2 client stores tokens in clear (`oauth2_authorized_client`), so either keep the tokens in `nuki_connection` or write an encrypting `OAuth2AuthorizedClientService`.
- `NukiConnections.callNuki` would get a valid access token first, refreshing it when it expires (one refresh at a time per owner); a failed refresh or a 401 marks the connection `DISCONNECTED`, as today. Pasted API tokens can stay as an alternative.
- Tests: WireMock for Nuki's authorize and token endpoints, `state` mismatch, expired access token, failed refresh.

**Other ideas**, roughly by value:

- A short guide for an owner who has just installed their Nuki lock, before the API token: activating Nuki Web for the lock (from the Nuki app) and signing in to Nuki Web. The token's steps are already on `/settings/nuki` ("Cómo conseguir el API token"); the guide could extend them there, in context and in the owner's language, rather than live outside the app. Check each step against Nuki's current app and help pages first, as their labels change.
- More languages than Spanish and English, for owners and guests alike: a bundle each, as the README's "Adding a language" says (`messages_<tag>.properties` and `Languages.SUPPORTED`), whose tests check every key and every page. The work is translating, and someone checking each translation; Catalan, French or German, say, depending on the owners and their guests.
- Re-encrypting stored Nuki tokens after an AEAD rotation, so that old keys can be dropped without owners pasting tokens again.
- Links with a maximum number of uses or a recurring schedule (`access_link` columns).
- Geofence as a deterrent (browsers accept fake locations: store only distance and accuracy, and tell the guest first).
- Nuki Opener, a local Nuki Bridge or MQTT adapter as another `LockProvider`, Nuki webhooks instead of polling the log.
- Cloudflare Access in front of the owner pages, excluding `/o/*` and `/api/open`.
