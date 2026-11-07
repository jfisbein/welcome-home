# AGENTS.md

Context for whoever maintains welcome-home next, person or agent. This file is the map, always read: what the app is for, the rules that hold everywhere, and which page to read for a task. The rest is in [docs/dev/](docs/dev/), one page per area, each starting with when to read it: read only those a task touches. [docs/features.md](docs/features.md) describes each feature in detail, [docs/user-guide.md](docs/user-guide.md) is for owners, [docs/security.md](docs/security.md) and [docs/deployment.md](docs/deployment.md) cover data and the stack, and [docs/runbook.md](docs/runbook.md) production operations.

## What it is

A private web app where a few owners connect their Nuki account and create date-bound links that open their door, and their building's entrance if a Nuki Opener opens it: a guest opens the link on their phone and slides to open, with no app, account or code.

**Roles**

- **Owner**: connects Nuki by authorizing welcome-home in Nuki (OAuth2) or with an API token, imports locks, creates, edits, regenerates and revokes links, reads each lock's history.
- **Admin**: an owner who can also invite owners, list them, create passkey recovery links, list and cancel both kinds of link, and delete owners. Admins never see other owners' locks, links or history.
- **Guest**: no account; has only a link.

**In production**: a Dockge stack on a private server, reached only through its own Cloudflare Tunnel. Image `ghcr.io/jfisbein/welcome-home`, at the major version's tag (`:v2`). The repository is public, so its public URL, its host and how to reach it are not here (Rules): `https://welcome-home.example.com` stands for the URL, and the owner has the rest.

## Requirements

Functional:

- Registration only by single-use invitation (72 h); sign-in only with passkeys, no passwords. The first admin comes from a command line invitation (`--bootstrap-admin`).
- An owner connects Nuki by authorizing welcome-home in Nuki (OAuth2), or by pasting a Nuki Web API token. Either is checked against Nuki before it is stored, encrypted; an OAuth2 access token is refreshed before it expires. A refresh it refuses, or a 401 that it repeats when asked once more, marks the connection `DISCONNECTED`; a daily job checks every connection, and an hourly one tries those disconnected in the last 7 days, connecting them again if Nuki accepts them.
- Locks are imported from Nuki on connecting it, when the owner asks and every day, smart locks and Nuki Openers alike; each has a name, a time zone (from Nuki where possible) and can be deactivated when it leaves the Nuki account. A smart lock may have one of the owner's Openers as its building entrance: its links then open both, the entrance first.
- A link has a label and a validity window entered in the lock's time zone and stored in UTC. Its state (pending, active, expired, revoked) is computed, never stored. It may be limited to certain days of the week with hours for each, one range per day, local to the lock.
- A link may ask its guest for a PIN (4 to 8 digits, the owner's choice, none by default), typed after the slide and checked on the server before the lock is touched; the owner can read it back, and a link whose wrong PINs are spent refuses everything for a while.
- A guest's GET never acts on the lock: only a POST after sliding to open unlatches (link previews in WhatsApp, Telegram or Slack make GETs). The guest learns whether the door opened, failed, or was refused in night mode.
- A lock may check that the guest is in front of the door before opening, asking one who seems far to confirm or refusing one clearly far; a guest whose browser does not say may always open by confirming.
- Every attempt with a valid link is recorded with IP address and country; attempts are purged after a retention (365 days), their IP addresses after a shorter one (90 days), and the guest page says so.
- Owners who lose their passkey get a recovery link (admin panel or `--recover-owner`); they keep their other passkeys.
- Owners get emails about their locks (opened, failed, denied, Nuki disconnected, a lock's problems) and choose which ones, once they have verified their address with the link sent when they register. Emails about their account, a link that may open the door from now on among them, always go.
- Owners see their account's activity: every sign-in, with its passkey, and every change to a link, with where it came from.
- Spanish, English, French, German and Italian: a guest's pages in their browser's language, an owner's pages and emails in the language they chose.

Non-functional:

- Owners are isolated from each other in the database itself (Row-Level Security), not only in code.
- Secrets at rest: Nuki tokens are encrypted (AEAD, bound to their owner and kind); links store no token at all (HMAC).
- Never two unlatches for one press: one action per lock at a time, and the unlatch is never retried once Nuki may have run it.
- Single instance: rate limits, the per-lock mutex and scheduled jobs live in memory in one process.
- GDPR: guests' IP addresses expire after 90 days, their browsers are not kept, guests are told on their page, and so does a link a year after it ended, with the guest's name in its label; a deleted owner's data is gone from backups after their 30-day retention.
- Upgrades within a major version are pulling the new image: no backup, no change on the server, and rolling back is pinning the previous release.

Out of scope so far: geofencing, push notifications, exceptions to a link's schedule (holidays), Ring to Open, native apps. Decided against on 2026-11-05 (the user's choices): links with a maximum number of uses; a local Nuki Bridge or MQTT in place of Nuki's Web API; and Cloudflare Access in front of the owner pages, as the app is to keep control of how owners sign in and authorize.

## Stack

| Piece                | Choice                                                                             |
|----------------------|------------------------------------------------------------------------------------|
| Language, build      | Java 25, Gradle 9 (Kotlin DSL, wrapper)                                             |
| Framework            | Spring Boot 4.1, Spring Security 7.1 (WebAuthn passkeys), Spring Modulith 2.1       |
| Web                  | Spring MVC, Thymeleaf, Pico CSS, a few small vanilla JS files (no build step)        |
| Data                 | PostgreSQL 18 (Alpine image), `JdbcClient` with records, Liquibase (XML changelogs) |
| Crypto               | Google Tink: AEAD AES-256-GCM (Nuki tokens), MAC HMAC-SHA256/128 (links)            |
| Nuki                 | Nuki Web API over `RestClient`, with timeouts and backoff                           |
| Rate limiting, cache | Bucket4j and Caffeine, in memory                                                    |
| Static analysis      | Error Prone + NullAway (JSpecify mode), javadoc doclint as errors                   |
| Email                | Spring Mail over SMTP (SMTP2GO in production)                                       |
| Tests                | JUnit 5, AssertJ, Testcontainers (Postgres), WireMock (Nuki), GreenMail (SMTP), webauthn4j-test, Playwright (Chromium) |
| Runtime              | Docker (JRE 25, non-root), Docker Compose in Dockge, cloudflared, GHCR, GitHub Actions |

## Rules

- **Nuki's unlatch**: Never call Nuki's unlatch for real outside a guest sliding to open: it opens a real door. Development uses a real Nuki token only for read-only calls.
- **Compatibility**: within a major version every release is backward compatible, migrations included, as production pulls `:vN` without reading release notes. Before changing the schema, a property, a secret, `deploy/` or a URL already sent, read [conventions](docs/dev/conventions.md).
- **What stays out of the repository**, which is public, history included: production's hostnames, addresses, container ids and paths on the server, and when anyone opened a door or which devices they have. Tokens and keysets, of course. What runs where belongs in the owner's private notes, what each release changed in its GitHub release.
- **Commits**: one per task, pushed to `main` once `./gradlew build` is green.
- **Start with `git fetch`**: the development clone has often been many commits behind `origin/main` ([testing](docs/dev/testing.md#practice)).
- **Docs are part of the task**: a change that makes a page in `docs/dev/` wrong updates it in the same commit, and so does anything learned that the next maintainer would need (a decision and its why, a test, something found about Nuki, an open question). It goes in its area's page, never here: this file only gets what holds for every task. A new area gets its page, with its "Read when", and a line under Modules and What to read for a task. Now and then the owner runs the `review-docs` skill (`.claude/skills/review-docs/`), which catches what this misses.

## Modules

A modular monolith: one Spring Boot app, its modules the packages under `welcomehome`, each used by the others only through its public types. [Architecture](docs/dev/architecture.md) says what each one does.

- `auth`, `admin`: owners' accounts, passkeys, invitations, recovery, sessions, the admin panel: [owners](docs/dev/owners.md)
- `nuki`: the only module that talks to Nuki: [nuki](docs/dev/nuki.md)
- `access`: the guest page and `POST /api/open`: [guest flow](docs/dev/guest-flow.md)
- `links`: access links, their schedules and PINs: [links](docs/dev/links.md)
- `audit`: attempts, history and owners' activity: [privacy](docs/dev/privacy.md)
- `notifications`: emails to owners: [notifications](docs/dev/notifications.md)
- `locks`, `preferences`, `config`, `crypto`, `db` and the root package: [architecture](docs/dev/architecture.md), [web](docs/dev/web.md), [database](docs/dev/database.md)

## What to read for a task

- Any code: [conventions](docs/dev/conventions.md), then [testing](docs/dev/testing.md) for how tests are written.
- A new class or module, a job, a command: [architecture](docs/dev/architecture.md).
- A table, a column, a query or a migration: [database](docs/dev/database.md).
- A page, a template, a script or a message: [web](docs/dev/web.md).
- Then the page of the area the task is in (Modules, above).
- A release, the image, `deploy/` or backups: [release](docs/dev/release.md).
- What to do next, or what only real use can tell: [status](docs/dev/status.md).

## Build and run

```sh
./gradlew build                   # everything CI runs
./gradlew generateKeysets         # development keysets in secrets/ (git-ignored)
docker compose up -d              # development Postgres on localhost:5432
./gradlew bootRun                 # the app on http://localhost:8080 with the dev profile
./gradlew rotateAeadKeyset --args=<file>
```

Releasing, the image and its workflow: [release](docs/dev/release.md).

## Where things are

| Path | What |
|------|------|
| `src/main/java/welcomehome/<module>/` | the modules above |
| `src/main/resources/db/changelog/` | Liquibase changelogs |
| `src/main/resources/templates/`, `static/` | Thymeleaf pages, CSS and JS |
| `deploy/` | production stack: `compose.yaml`, `db-init/`, `backup/`, `restore.sh` |
| `compose.yaml` | development Postgres |
| `docs/dev/` | for maintainers: one page per area, from this file |
| `.claude/skills/` | skills for Claude Code: `review-docs`, the periodic review of these docs |
| `docs/runbook.md` | production operations |
| `docs/design/` | designs of what is not built yet |
| `docs/archive/` | earlier reviews and plans, for their reasoning only |
| `docs/features.md` | each feature in detail, its properties |
| `docs/user-guide.md` | for owners: how to use it, what to do when a guest cannot get in |
| `docs/security.md`, `docs/deployment.md` | roles, keysets and web security; the image, the stack and backups |
| `LICENSE`, `SECURITY.md` | Apache-2.0; how to report a vulnerability, privately through GitHub |
