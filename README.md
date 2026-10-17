# welcome-home

A private web app where several owners connect their Nuki account and create unique, date-bound links that open the door without an app or codes.

## Requirements

- JDK 25
- Docker (Postgres for development and Testcontainers for tests)

## Usage

```sh
./gradlew build          # compile and run the tests (starts Postgres with Testcontainers)
docker compose up -d     # development Postgres on localhost:5432
./gradlew bootRun        # start the app with the dev profile on http://localhost:8080
```

Quick check: `curl http://localhost:8080/actuator/health`.

## Database

`docker/db-init/01-create-roles.sh` creates two roles when an empty Postgres starts:

- `migrator`: owns the database and the schema; migrations run as this role.
- `app_user`: the application's user; no ownership and no `BYPASSRLS`, only the grants that migrations give it.

Owner data is isolated with Row-Level Security. `OwnerTransactions` runs each owner's work in a transaction that sets `app.owner_id` with `set_config(..., true)`, so the setting ends with the transaction.

The script only runs on an empty volume. To recreate the development database: `docker compose down -v`.

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
