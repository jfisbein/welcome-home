# Security and data

How owners' data is kept apart and secrets are kept. For why, see the Decisions in [AGENTS.md](../AGENTS.md).

## Database

`deploy/db-init/01-create-roles.sh` creates the roles when an empty Postgres starts:

- `migrator`: owns the database and the schema; migrations run as this role.
- `app_user`: the application's user; no ownership and no `BYPASSRLS`, only the grants that migrations give it.
- `backup_reader`, only when its password is given (`BACKUP_READER_PASSWORD` or `BACKUP_READER_PASSWORD_FILE`): an off-site backup tool's, with `BYPASSRLS` and `pg_read_all_data`, so it reads every row and writes nothing.

Liquibase runs the changesets in `src/main/resources/db/changelog/` at startup as `migrator` (`spring.liquibase.user` / `spring.liquibase.password`), while the app connects as `app_user` (`spring.datasource.username` / `spring.datasource.password`). Outside the dev profile, set them with the `SPRING_LIQUIBASE_*` and `SPRING_DATASOURCE_*` environment variables.

Owner data is isolated with Row-Level Security. `OwnerTransactions` runs each owner's work in a transaction that sets `app.owner_id` with `set_config(..., true)`, so the setting ends with the transaction. It joins a transaction already under way only for the same owner: the setting would last to the end of it, so joining one bound to another owner throws.

On top of that, every query filters by the current owner, and `OwnerIsolationTests` checks it end to end: each route that takes a lock or link id answers 404 when the id belongs to another owner, and leaves that owner's data unchanged. A guard test lists the app's routes with an id and fails if one is missing from it; routes whose id is a guest's or invitee's token are listed apart.

The script only runs on an empty volume. To recreate the development database: `docker compose down -v`.

## Keysets

The app needs two Tink keysets and does not start without them:

| Variable           | Keyset                    | Used for                     |
|--------------------|---------------------------|------------------------------|
| `AEAD_KEYSET_FILE` | AES-256-GCM               | Encrypting Nuki tokens       |
| `MAC_KEYSET_FILE`  | HMAC-SHA256, 128-bit tag  | Signing access links         |

`./gradlew generateKeysets` writes both to `secrets/`, readable only by the current user, and never overwrites existing files. In production they are Docker secrets, backed up separately from the database: losing them means every owner connecting Nuki again and regenerating every access link. `./gradlew rotateAeadKeyset --args=<file>` adds a new primary key to an AEAD keyset; the earlier keys keep decrypting the tokens stored with them.

## Web security

- CSRF: every POST needs Spring Security's token; pages put it in a meta tag for `static/js/webauthn.js`. The exceptions are `POST /api/open`, whose credential is the link's token in the body: guest pages keep no session and carry no CSRF token, so a page a guest opened hours earlier still opens the door; and `POST /nuki/webhook`, whose credential is Nuki's signature of the body.
- Content Security Policy: only the app's own scripts, styles and images (plus `data:` images for Pico CSS), nothing inline, no framing. Cloudflare features that inject scripts (Rocket Loader, email obfuscation, automatic Web Analytics) are blocked by it and must stay off.
- `Referrer-Policy: no-referrer`, so that invitation and access tokens in URLs never leak to other sites.
- HSTS for a year, sent only over HTTPS. Behind Cloudflare Tunnel the app learns that a request came over HTTPS from `X-Forwarded-Proto` (`server.forward-headers-strategy=framework`).
- The session cookie is `Secure`, `HttpOnly` and `SameSite=Lax`. The dev profile drops `Secure`, since `http://localhost` has no HTTPS. The session travels only in that cookie (`server.servlet.session.tracking-modes: cookie`), never as `;jsessionid=` in a URL, where logs, history and referrers would keep it.
- Errors: browsers get the app's own page (`templates/error.html`, chosen by `ErrorPages`), with the error's status code, in the visitor's language and theme: one text for a 404, which a missing id and another owner's id both are, one for a 403, and one for other 4xx and for 5xx. A signed-in owner gets the menu; anyone else a page that starts no session. `/error` is public so that a guest's error on a public route (say, a GET to `/api/open`) reaches it instead of a sign-in redirect, while an unknown address still sends a visitor who is not signed in to `/login`, so no route shows that it exists. Scripts that ask for JSON, as the guest page's does, still get Spring Boot's JSON.
- Guests' tokens never reach the logs. Spring logs request paths at DEBUG, so `logback-spring.xml` masks every `/o/{token}` as `/o/***` in all messages, and the body of `POST /api/open` prints without its token. Tomcat's access log stays off.
