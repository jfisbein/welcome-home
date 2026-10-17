# welcome-home

A private web app where several owners connect their Nuki account and create unique, date-bound links that open the door without an app or codes.

## Requirements

- JDK 25
- Docker (Postgres for development and Testcontainers for tests)

## Usage

```sh
./gradlew build          # compile, run the tests (Postgres with Testcontainers) and check javadoc
./gradlew generateKeysets  # once: development keysets in secrets/ (git-ignored)
docker compose up -d     # development Postgres on localhost:5432
./gradlew bootRun        # start the app with the dev profile on http://localhost:8080
```

Quick check: `curl http://localhost:8080/actuator/health`.

## Database

`docker/db-init/01-create-roles.sh` creates two roles when an empty Postgres starts:

- `migrator`: owns the database and the schema; migrations run as this role.
- `app_user`: the application's user; no ownership and no `BYPASSRLS`, only the grants that migrations give it.

Liquibase runs the changesets in `src/main/resources/db/changelog/` at startup as `migrator` (`spring.liquibase.user` / `spring.liquibase.password`), while the app connects as `app_user` (`spring.datasource.username` / `spring.datasource.password`). Outside the dev profile, set them with the `SPRING_LIQUIBASE_*` and `SPRING_DATASOURCE_*` environment variables.

Owner data is isolated with Row-Level Security. `OwnerTransactions` runs each owner's work in a transaction that sets `app.owner_id` with `set_config(..., true)`, so the setting ends with the transaction.

The script only runs on an empty volume. To recreate the development database: `docker compose down -v`.

## Keysets

The app needs two Tink keysets and does not start without them:

| Variable           | Keyset                    | Used for                     |
|--------------------|---------------------------|------------------------------|
| `AEAD_KEYSET_FILE` | AES-256-GCM               | Encrypting Nuki API tokens   |
| `MAC_KEYSET_FILE`  | HMAC-SHA256, 128-bit tag  | Signing access links         |

`./gradlew generateKeysets` writes both to `secrets/`, readable only by the current user, and never overwrites existing files. In production they are Docker secrets, backed up separately from the database: losing them means pasting every Nuki API token again and regenerating every access link.

## First admin

Owners register only through single-use invitations, valid for 72 hours. The first admin's invitation comes from a one-off command that prints the link and exits; it refuses when an admin already exists:

```sh
./gradlew bootRun --args='--bootstrap-admin'          # development
docker compose run --rm app --bootstrap-admin         # production
```

Opening an invitation link asks for an email and creates a passkey on that device; owners then sign in with it at `/login`. There are no passwords.

Admins invite further owners from the admin panel (`/admin/owners`, linked from the owner's panel): each invitation has a note on who it is for and may make the new owner an admin too. Its link is shown only once, since only a hash of the token is stored. The panel lists owners' emails and registration dates, never their locks or links: Row-Level Security applies to admins like to any other owner.

The app needs `BASE_URL` (its public URL, e.g. `https://welcome.example`) to build invitation and access links. Its host is also the passkeys' relying party: passkeys created for one domain do not work on another.

## Nuki

The `nuki` module is the only one that talks to Nuki, through the Nuki Web API ([definition](https://api.nuki.io/static/swagger/swagger.json)) with each owner's API token. Other modules use the `LockProvider` interface, which takes an owner id: the module finds and decrypts that owner's token itself, so tokens never leave it in clear.

Owners connect at `/nuki/connection` by pasting an API token generated in Nuki Web (Menu → API) with permission to view their locks, operate them and read their log. The token is stored only after `GET /smartlock` accepts it, encrypted for its owner, and it is never shown again. Pasting a new token replaces the old one and reconnects a disconnected account.

When Nuki answers 401 to a stored token, the connection becomes `DISCONNECTED`: links stop opening and the owner's panel asks for a new token. A job checks every connection daily (`welcomehome.nuki.check-cron`, 08:00 server time by default), so that the owner learns about a revoked token before a guest does. A 403 (a permission missing) or an unanswered call does not disconnect.

Disconnecting (`DELETE /nuki/connection`, the "Desconectar Nuki" button) deletes the token. Locks and links stay as they are, without any change of state, but cannot open the door until the owner connects again: guests see "not available".

Calls time out and are retried with exponential backoff: on rate limiting (429, honoring `Retry-After`), and for reads also on server errors and timeouts. The unlatch action is retried only when Nuki cannot have run it (429, or no connection made), so a door never opens twice. The defaults, under `welcomehome.nuki.*`:

| Property          | Default               |
|-------------------|-----------------------|
| `base-url`        | `https://api.nuki.io` |
| `connect-timeout` | `5s`                  |
| `read-timeout`    | `10s`                 |
| `max-attempts`    | `3`                   |
| `initial-backoff` | `500ms`, doubling     |
| `max-backoff`     | `5s`                  |

## Locks

`/locks` lists the owner's locks; "Sincronizar con Nuki" (`POST /locks/sync`) brings them in line with the Nuki account. New locks are imported with their Nuki name and time zone, which the owner can change. Nuki reports the time zone as an id from [its list](https://docs.nuki.io/guide/overview/concepts/), with one zone per region (a lock in Madrid reports `Europe/Berlin`, which has the same rules); a lock without one gets the server's time zone. Later syncs keep those choices. Locks no longer in the account become inactive, keeping their links; if they come back, they are active again. Each sync also links the locks to the owner's current Nuki connection.

Each lock's settings page (`GET /locks/{id}`, saved with `PATCH /locks/{id}`) changes its name, which guests see, and its time zone, one of the IANA region zones or UTC. Link dates are entered and shown in the lock's time zone but stored in UTC, so changing the time zone changes how existing links are shown, not when they are valid.

The same page shows the lock's live state from Nuki: position, connectivity, battery and Auto Lock. Without Auto Lock the door stays unlocked after a guest opens it, so the page warns about it, unless the owner ticks that the door is relocked some other way (for example, with home automation). If Nuki cannot be reached, the page still shows the settings.

## Access links

A lock's links page (`GET /locks/{id}/links`) creates links (`POST /locks/{id}/links`): a label, and a start and an end entered in the lock's time zone and stored in UTC. A link's URL is `{BASE_URL}/o/{link_id}.{mac}`, where the MAC covers the link id and its `token_version` (see `LinkTokenSigner`). The database stores no token: the URL is computed again when needed, and incrementing `token_version` invalidates every earlier URL. Inactive locks get no new links.

The same page lists the lock's links with their state, computed and never stored (`LinkStatus`): revoked if revoked, otherwise pending before the start, active from the start (inclusive) to the end (exclusive), and expired after. Pending and active links can be copied, or shared through the device's share sheet where the browser supports it.

"Revocar" (`POST /links/{id}/revoke`) revokes a link at once and for good: from then on it opens nothing, whatever its dates. Revoking again keeps the first revocation time.

"Regenerar" (`POST /links/{id}/regenerate`), for a URL that leaked, increments the link's `token_version`: every earlier URL stops working and the page shows the new one. Dates, label and state stay the same; revoked links are not regenerated.

"Editar" (`GET /links/{id}`, saved with `PATCH /links/{id}`) changes a link's label and dates, with the same checks as creating one; an expired link can be extended this way. Its URL stays the same. Revoked links cannot be edited.

## Guest page

`GET /o/{token}` is public. It reads the link id from the token, finds the link with `resolve_link` (no owner context needed) and trusts it only if the token's MAC is valid for the link's current `token_version`. A malformed token, an unknown link and a wrong or outdated MAC all get the same "Enlace no válido" page (404), so that guests cannot tell which links exist. The page shows the lock's name and the link's validity in the lock's time zone, never the owner's label, and an "Abrir" button only while the link is active and its lock too.

A GET never opens the door, nor calls `LockProvider` at all: messaging apps fetch links to preview them. Opening is a POST after the guest presses the button. The page asks search engines not to index it (`X-Robots-Tag` and `<meta name="robots">`).

Pressing "Abrir" sends `POST /api/open` with the token in the body. The checks run in this order: rate limit per IP address; the link exists, its MAC is valid and it is not revoked; rate limit per link; within its dates; the lock is active; the owner's Nuki connection is OK. Only then is the door unlatched, in the context of the owner that `resolve_link` returned. Nuki runs actions asynchronously, so after it accepts the unlatch the app reads the lock's log until an unlatch from after the request shows up: the guest learns whether the door opened, whether the lock failed (for example, motor blocked) or refused it in night mode, or that the lock did not confirm in time (`welcomehome.access.confirmation.timeout`, 15 s, read every `interval`, 2 s). Only one action runs per lock at a time: a guest who presses "Abrir" while the same lock is being opened waits for that action and gets its result, so Nuki receives a single action. Every attempt with a valid link is recorded in the owner's history (`access_event`), with the guest's IP address and browser.

Rate limits are kept in memory, which is enough for a single instance, and set in `application.properties`:

| Property                                  | Default |
|-------------------------------------------|---------|
| `welcomehome.access.rate-limit.per-ip`    | `10`    |
| `welcomehome.access.rate-limit.per-link`  | `5`     |
| `welcomehome.access.rate-limit.period`    | `1m`    |

## Web security

- CSRF: every POST needs Spring Security's token; pages put it in a meta tag for `static/js/webauthn.js`.
- Content Security Policy: only the app's own scripts, styles and images (plus `data:` images for Pico CSS), nothing inline, no framing. Cloudflare features that inject scripts (Rocket Loader, email obfuscation, automatic Web Analytics) are blocked by it and must stay off.
- `Referrer-Policy: no-referrer`, so that invitation and access tokens in URLs never leak to other sites.
- HSTS for a year, sent only over HTTPS. Behind Cloudflare Tunnel the app learns that a request came over HTTPS from `X-Forwarded-Proto` (`server.forward-headers-strategy=framework`).
- The session cookie is `Secure`, `HttpOnly` and `SameSite=Lax`. The dev profile drops `Secure`, since `http://localhost` has no HTTPS.

## Modules

Packages under `welcomehome`, verified with Spring Modulith (`ModularityTests`):

| Module   | Responsibility                                                    |
|----------|-------------------------------------------------------------------|
| `auth`   | Owner registration and login with passkeys                        |
| `admin`  | Invitations and the owner list                                    |
| `crypto` | Keysets, encryption of Nuki API tokens and signing of links       |
| `nuki`   | Nuki Web API client behind `LockProvider`                         |
| `locks`  | Locks: sync, configuration and state                              |
| `links`  | Access links: create, revoke, regenerate and edit                 |
| `access` | Guest flow: validates the link and opens the door                 |
| `audit`  | Access events and their retention                                 |
| `db`     | Shared database infrastructure: owner-bound transactions for RLS  |
