# Security and data

How owners' data is kept apart and secrets are kept. For why, see the Decisions in [database](dev/database.md#decisions), [owners](dev/owners.md#decisions), [guest flow](dev/guest-flow.md#decisions) and [release](dev/release.md#decisions).

## Database

`deploy/db-init/01-create-roles.sh` creates the roles when an empty Postgres starts:

- `migrator`: owns the database and the schema; migrations run as this role.
- `app_user`: the application's user; no ownership and no `BYPASSRLS`, only the grants that migrations give it.
- `backup_reader`, only when its password is given (`BACKUP_READER_PASSWORD` or `BACKUP_READER_PASSWORD_FILE`): an off-site backup tool's, with `BYPASSRLS` and `pg_read_all_data`, so it reads every row and writes nothing.

Liquibase runs the changesets in `src/main/resources/db/changelog/` at startup as `migrator` (`spring.liquibase.user` / `spring.liquibase.password`), while the app connects as `app_user` (`spring.datasource.username` / `spring.datasource.password`). Outside the dev profile, set the users with the `SPRING_LIQUIBASE_USER` and `SPRING_DATASOURCE_USERNAME` environment variables; the passwords are never in the environment, where `docker inspect` shows them: production's stack mounts them as files that Spring reads as properties (`spring.config.import=configtree:`, see [deployment](deployment.md)).

Owner data is isolated with Row-Level Security. `OwnerTransactions` runs each owner's work in a transaction that sets `app.owner_id` with `set_config(..., true)`, so the setting ends with the transaction. It joins a transaction already under way only for the same owner: the setting would last to the end of it, so joining one bound to another owner throws.

On top of that, every query filters by the current owner, and `OwnerIsolationTests` checks it end to end: each route that takes a lock or link id answers 404 when the id belongs to another owner, and leaves that owner's data unchanged. A guard test lists the app's routes with an id and fails if one is missing from it; routes whose id is a guest's or invitee's token are listed apart.

The script only runs on an empty volume. To recreate the development database: `docker compose down -v`.

## Keysets

The app needs two Tink keysets and does not start without them:

| Variable           | Keyset                    | Used for                     |
|--------------------|---------------------------|------------------------------|
| `AEAD_KEYSET_FILE` | AES-256-GCM               | Encrypting Nuki tokens and links' PINs |
| `MAC_KEYSET_FILE`  | HMAC-SHA256, 128-bit tag  | Signing access links         |

`./gradlew generateKeysets` writes both to `secrets/`, readable only by the current user, and never overwrites existing files. In production they are Docker secrets, backed up separately from the database: losing them means every owner connecting Nuki again and regenerating every access link. `./gradlew rotateAeadKeyset --args=<file>` adds a new primary key to an AEAD keyset; the earlier keys keep decrypting the tokens stored with them, and on starting the app encrypts those tokens again with the new key, so that the earlier keys can then leave the keyset (runbook, "Rotate the AEAD keyset").

## Links' PINs

A link may have a PIN that its guest types after sliding. It is stored encrypted with the AEAD keyset (bound to its owner and link, so a copy in another row does not decrypt) so that its owner can read it back, which hashing would not allow. So a database copy alone does not show PINs, but whoever also holds the AEAD keyset can read them, as they could the Nuki tokens. Its strength is the limit, not the secret: a 4 to 8 digit code can be guessed, so wrong PINs are counted per link twice over. A few in a while, in memory, and the link refuses every attempt until they come back; that only slows guessing down, to 480 guesses a day, and a restart forgets it. And a total, 20 in a row, on the link's row: past it the link is locked for everyone until its owner edits it or gives it a new URL, and the owner is emailed whatever their preferences. A right PIN starts the count again, so that a guest's own slips never lock them out (the user's choice, 2026-11-19): 20 guesses at a 4-digit PIN are 1 in 500, and whoever guesses while a guest keeps opening gets 20 more after each opening, at the pace the limit in memory allows; that the owner is emailed about refused attempts is what tells of it (features, Access links). The PIN adds to the link's MAC: it does not replace it, and a leaked URL alone no longer opens the door.

## Web security

- CSRF: every POST needs Spring Security's token; pages put it in a meta tag for `static/js/webauthn.js`. The exceptions are `POST /api/open`, whose credential is the link's token in the body: guest pages keep no session and carry no CSRF token, so a page a guest opened hours earlier still opens the door; and `POST /nuki/webhook`, whose credential is Nuki's signature of the body. The token lives in the session, so a form from a page whose session has ended, by a restart or after 30 minutes without use, cannot pass: rather than a 403, `EndedSessionFormHandler` sends it to `/login?expired`, which says that nothing was saved, and "Salir" to `/login?logout`, as the owner is out already. Only when the browser sent a session the server no longer has, and only for an HTML form: a bad token with a live session or none, and a script's request, still get 403.
- Content Security Policy: only the app's own scripts, styles and images (plus `data:` images for Pico CSS), nothing inline, no framing. Cloudflare features that inject scripts (Rocket Loader, email obfuscation, automatic Web Analytics) are blocked by it and must stay off.
- `Referrer-Policy: no-referrer`, so that invitation and access tokens in URLs never leak to other sites.
- HSTS for a year, sent only over HTTPS. Behind Cloudflare Tunnel the app learns that a request came over HTTPS from `X-Forwarded-Proto` (`server.forward-headers-strategy=framework`).
- The session cookie is `__Host-session`, `Secure`, `HttpOnly` and `SameSite=Lax`. The `__Host-` prefix makes browsers take it only if it is `Secure`, for this host alone and for every path, so a page on another subdomain of the same parent domain cannot plant a session here. The dev profile calls it `JSESSIONID` and drops `Secure`, since `http://localhost` has no HTTPS.
- Signing in, with a passkey or by registering from an invitation or a recovery link, gives the session a new id and a new CSRF token (`SecurityConfiguration.ON_SIGN_IN`): whoever planted the session beforehand knew both. The session travels only in that cookie (`server.servlet.session.tracking-modes: cookie`), never as `;jsessionid=` in a URL, where logs, history and referrers would keep it.
- What would hand an account to whoever sits at its owner's unlocked browser asks for a recent sign-in: changing the email, where every notice goes, and adding a passkey need the session to have signed in within 10 minutes (`RecentSignInFilter`, before the chain authorizes, so that it also covers Spring Security's own `/webauthn/register` endpoints); otherwise the owner confirms with a passkey at `/settings/confirm`. The verified address an owner had keeps getting the notices about their account for 7 days after a change. Connecting Nuki is not guarded: it gives whoever does it nothing of the owner's.
- A passkey recovery link lasts 4 hours, an invitation 72; using either is in the owner's activity, a recovery marked as one.
- Errors: browsers get the app's own page (`templates/error.html`, chosen by `ErrorPages`), with the error's status code, in the visitor's language and theme: one text for a 404, which a missing id and another owner's id both are, one for a 403, and one for other 4xx and for 5xx. A signed-in owner gets the menu; anyone else a page that starts no session. `/error` is public so that a guest's error on a public route (say, a GET to `/api/open`) reaches it instead of a sign-in redirect, while an unknown address still sends a visitor who is not signed in to `/login`, so no route shows that it exists. Scripts that ask for JSON, as the guest page's does, still get Spring Boot's JSON.
- Guests' tokens never reach the logs. Spring logs request paths at DEBUG, so `logback-spring.xml` masks every `/o/{token}` as `/o/***` in all messages, and the body of `POST /api/open` prints without its token. Tomcat's access log stays off.
