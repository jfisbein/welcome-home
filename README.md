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
