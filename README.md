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

The compiler runs [Error Prone](https://errorprone.info) with [NullAway](https://github.com/uber/NullAway) in JSpecify mode: in the app's code nothing is null unless it is `@Nullable` (`org.jspecify.annotations`), and the build fails where a possibly null value could be dereferenced or passed where it may not. Every package is `@NullMarked` in its `package-info.java`, so the IDE knows it too. Tests are not checked for nullness.

## Database

`deploy/db-init/01-create-roles.sh` creates two roles when an empty Postgres starts:

- `migrator`: owns the database and the schema; migrations run as this role.
- `app_user`: the application's user; no ownership and no `BYPASSRLS`, only the grants that migrations give it.

Liquibase runs the changesets in `src/main/resources/db/changelog/` at startup as `migrator` (`spring.liquibase.user` / `spring.liquibase.password`), while the app connects as `app_user` (`spring.datasource.username` / `spring.datasource.password`). Outside the dev profile, set them with the `SPRING_LIQUIBASE_*` and `SPRING_DATASOURCE_*` environment variables.

Owner data is isolated with Row-Level Security. `OwnerTransactions` runs each owner's work in a transaction that sets `app.owner_id` with `set_config(..., true)`, so the setting ends with the transaction.

On top of that, every query filters by the current owner, and `OwnerIsolationTests` checks it end to end: each route that takes a lock or link id answers 404 when the id belongs to another owner, and leaves that owner's data unchanged. A guard test lists the app's routes with an id and fails if one is missing from it; routes whose id is a guest's or invitee's token are listed apart.

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
| `connect-timeout` | `PT5S`                |
| `read-timeout`    | `PT10S`               |
| `max-attempts`    | `3`                   |
| `initial-backoff` | `PT0.5S`, doubling    |
| `max-backoff`     | `PT5S`                |

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

`GET /o/{token}` is public. It reads the link id from the token, finds the link with `resolve_link` (no owner context needed) and trusts it only if the token's MAC is valid for the link's current `token_version`. A malformed token, an unknown link and a wrong or outdated MAC all get the same "Enlace no válido" page (404), so that guests cannot tell which links exist. The page shows the lock's name and the link's validity in the lock's time zone, never the owner's label, and an "Abrir" button only while the link is active and its door is available: the lock is active and the owner's Nuki connection is OK. The page and `POST /api/open` share that check, which reads only the database, and show the same messages: not yet valid (with its start), expired, revoked, or door unavailable.

A GET never opens the door, nor calls `LockProvider` at all: messaging apps fetch links to preview them. Opening is a POST after the guest presses the button. The page asks search engines not to index it (`X-Robots-Tag` and `<meta name="robots">`).

Pressing "Abrir" sends `POST /api/open` with the token in the body. The checks run in this order: rate limit per IP address; the link exists, its MAC is valid and it is not revoked; rate limit per link; within its dates; the lock is active; the owner's Nuki connection is OK. Only then is the door unlatched, in the context of the owner that `resolve_link` returned. Nuki runs actions asynchronously, so after it accepts the unlatch the app reads the lock's log until an unlatch from after the request shows up: the guest learns whether the door opened, whether the lock failed (for example, motor blocked) or refused it in night mode, or that the lock did not confirm in time (`welcomehome.access.confirmation.timeout`, `PT15S`, read every `interval`, `PT2S`). Only one action runs per lock at a time: a guest who presses "Abrir" while the same lock is being opened waits for that action and gets its result, so Nuki receives a single action. Every attempt with a valid link is recorded in the owner's history (`access_event`), with the guest's IP address and browser.

Rate limits are kept in memory, which is enough for a single instance, and set in `application.yaml`:

| Property                                  | Default |
|-------------------------------------------|---------|
| `welcomehome.access.rate-limit.per-ip`    | `10`    |
| `welcomehome.access.rate-limit.per-link`  | `5`     |
| `welcomehome.access.rate-limit.period`    | `PT1M`  |

The guest's IP address, for the rate limit and the history, comes from `CF-Connecting-IP`, which Cloudflare sets on every request, overwriting what the client sent. `X-Forwarded-For` would not do: it starts with whatever the client sent. Trusting the header is safe because the tunnel is the only way in; if the app were ever reachable otherwise, set `welcomehome.access.client-ip-header` blank to use the address the request came from.

## History

`GET /locks/{id}/history` lists the guests' attempts on a lock, newest first, in the lock's time zone: when, with which link, the outcome in words (for example "Denegado: caducado" or "Error: modo nocturno") and the guest's IP address. `?link={id}` narrows it to one link; the lock page and each link in the list lead there. Pages hold 50 attempts; "Anteriores" adds `before=` with the date of the last one shown, so a page never shifts as new attempts come in. Row-Level Security keeps each owner to their own history, and a lock or link of someone else's is a 404.

Attempts hold IP addresses, which are personal data, so they are kept for `welcomehome.audit.retention` (`P365D`) only. A job deletes older ones every night (`welcomehome.audit.purge-cron`, `0 30 3 * * *`, in the server's time zone), each owner in their own transaction under Row-Level Security.

## Web security

- CSRF: every POST needs Spring Security's token; pages put it in a meta tag for `static/js/webauthn.js`.
- Content Security Policy: only the app's own scripts, styles and images (plus `data:` images for Pico CSS), nothing inline, no framing. Cloudflare features that inject scripts (Rocket Loader, email obfuscation, automatic Web Analytics) are blocked by it and must stay off.
- `Referrer-Policy: no-referrer`, so that invitation and access tokens in URLs never leak to other sites.
- HSTS for a year, sent only over HTTPS. Behind Cloudflare Tunnel the app learns that a request came over HTTPS from `X-Forwarded-Proto` (`server.forward-headers-strategy=framework`).
- The session cookie is `Secure`, `HttpOnly` and `SameSite=Lax`. The dev profile drops `Secure`, since `http://localhost` has no HTTPS.
- Guests' tokens never reach the logs. Spring logs request paths at DEBUG, so `logback-spring.xml` masks every `/o/{token}` as `/o/***` in all messages, and the body of `POST /api/open` prints without its token. Tomcat's access log stays off.

## Docker image

```sh
docker build -t welcome-home .
```

The `Dockerfile` builds the jar with JDK 25 and runs it on a JRE 25 (`eclipse-temurin`, Ubuntu Noble) as the unprivileged user `app` (uid 10001), from Spring Boot's layers, so a new release only changes the application layer. Tests do not run in the image build. `.dockerignore` lets in only the build files and `src/`, so keysets never end up in the image.

The container listens on 8080 and is configured with environment variables:

| Variable                                                   | Value                                              |
|------------------------------------------------------------|----------------------------------------------------|
| `BASE_URL`                                                 | the public URL, such as `https://welcome-home.alcornoque.top` |
| `TZ`                                                       | the server's time zone, for the daily jobs, such as `Europe/Madrid` |
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD`          | Postgres as `app_user`                             |
| `SPRING_LIQUIBASE_USER`, `SPRING_LIQUIBASE_PASSWORD`       | Postgres as `migrator`                             |
| `AEAD_KEYSET_FILE`, `MAC_KEYSET_FILE`                      | paths of the mounted keysets                       |

The keysets are mounted read-only and must be readable by uid 10001. The image has no curl or wget; a health check can use bash: `exec 3<>/dev/tcp/localhost/8080; printf 'GET /actuator/health HTTP/1.0\r\n\r\n' >&3; grep -q UP <&3`.

## Production

Every push to `main` that passes `./gradlew build` publishes the image to GHCR as `ghcr.io/jfisbein/welcome-home`, tagged `latest` and `sha-<commit>` (`.github/workflows/image.yml`).

`deploy/` is the Dockge stack: copy it to the stack's folder on the server. `compose.yaml` runs four services and publishes no port:

- `db`: Postgres 17. `db-init/01-create-roles.sh` creates `migrator` and `app_user` on the first start. Its network is internal: it has no way out.
- `app`: the image, with a read-only file system, no capabilities, `TZ=Europe/Madrid` and `BASE_URL=https://welcome-home.alcornoque.top`. It reads its database passwords as properties from `/run/secrets/config/` (`spring.config.import=configtree:`), not from environment variables.
- `backup`: dumps the database to `backups/` (see [Backups](#backups)).
- `cloudflared`: connects the tunnel, whose public hostname `welcome-home.alcornoque.top` points to `http://app:8080`. The tunnel is the only way in, which is what makes `CF-Connecting-IP` and `X-Forwarded-Proto` trustworthy.

Before the first `docker compose up`, create `secrets/` next to `compose.yaml`:

| File                 | Content                                                  |
|----------------------|----------------------------------------------------------|
| `postgres_password`  | Postgres superuser's password, such as `openssl rand -base64 24` |
| `migrator_password`  | `migrator`'s password                                    |
| `app_user_password`  | `app_user`'s password                                    |
| `aead-keyset.json`   | the AEAD keyset (`./gradlew generateKeysets`)            |
| `mac-keyset.json`    | the MAC keyset                                           |
| `tunnel_token`       | the Cloudflare tunnel's token                            |

Write the passwords without a trailing newline (`printf`, or `tr -d '\n'`). Compose mounts each file with its host permissions and the containers run as different users (Postgres 999, the app 10001, cloudflared 65532), so: `chmod 700 secrets` (only root on the host can read them) and `chmod 444 secrets/*` (each container sees only its own). The database passwords are only read on the first start, when the volume is empty.

Create `backups/` as well, owned by the `backup` service's user: `install -d -o 999 -g 999 -m 700 backups`.

### Backups

The `backup` service dumps the database with `pg_dump` (custom format) to `backups/welcome_home-<date>-<time>.dump` when it starts and every day at 04:00, after the access events' purge, and deletes the dumps older than 30 days (`BACKUP_TIME`, `BACKUP_RETENTION_DAYS`). It dumps as the Postgres superuser, as Row-Level Security would hide every row from any other role. Its health check fails when the last dump is more than 25 hours old. To take a dump now, such as before an upgrade: `docker compose exec backup /backup/backup.sh now`.

- **Off the server**: a restic job (such as zerobyte) copies `backups/` every day to another provider, such as Backblaze B2, after 04:00. Keep its snapshots for 30 days too, so that 30 days after an owner is deleted no copy of their data is left.
- **Keysets**: never with the dumps. Keep `aead-keyset.json` and `mac-keyset.json` in a password manager (Vaultwarden), and update them there whenever they change. A dump without them still restores, but every Nuki API token must be pasted again and every access link regenerated. The other secrets can be generated anew on a new server.

The dumps hold the encrypted Nuki API tokens, owners' e-mail addresses, link labels and guests' IP addresses: only root on the server can read them.

To restore one, with the stack's `compose.yaml` next to the script:

```sh
./restore.sh backups/welcome_home-20261018-040000.dump
```

It stops `app` and `backup`, drops the database and recreates it from the dump with its owner, grants and Liquibase history, and starts both again: the app migrates it if the dump comes from an older version, and `backup` takes a dump of the restored database. On a new server, create `secrets/` (with the keysets from the password manager) and `backups/` first and start `db`, so that `db-init` creates the roles; the passwords may be new ones.

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
