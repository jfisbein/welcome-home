# Database

Read when: adding or changing a table, a column, a query or a migration; Row-Level Security, Postgres roles, what is encrypted in the database.

See also: [conventions](conventions.md) (compatibility: what a migration may do within a major version), [architecture](architecture.md), [release](release.md) (backups).

## Data model

**Data model** (`src/main/resources/db/changelog/changes/`): `owner` (`email`, `language`, and the email's verification: `email_verified_at`, the link's hash and expiry; the only columns `app_user` may update), `invitation` (also recovery links: `recovers_owner_id`), `nuki_connection` (`credential`: `API_TOKEN` or `OAUTH2`; `access_token_encrypted`, and for OAuth2 `refresh_token_encrypted` and `access_token_expires_at`), `lock` (`device_type`: `SMART_LOCK` or `OPENER`; `entrance_lock_id`, the Opener at a smart lock's building entrance; `latitude` and `longitude`, from Nuki or the owner, both or neither, which only the owner sees; `location_check` and `location_radius_m`, whether and how far a guest's distance to the door is checked), `access_link` (`token_version`; `pin_encrypted`, the optional PIN, AEAD bound to its owner and link, NULL for none), `access_link_schedule` (a link's days and hours: `day_of_week`, `opens_at`, `closes_at`, local to the lock; no rows, any time), `access_event` (`ip`, `country`, and `entrance_lock_id` for an attempt at the entrance; `lock_id` is always the link's lock; `distance_m`, `accuracy_m` and `location_confirmed` for a door that checks where its guests are, never their position), `notification_preference`, `owner_activity` (`kind`, `lock_id` and `link_id` without foreign keys, as an entry outlives its link, `passkey`, `ip`, `country`), and Spring Security's `user_entities` and `user_credentials`. Every table with `owner_id` has Row-Level Security with a policy that fails closed (`owner_id = nullif(current_setting('app.owner_id', true), '')::uuid`). `owner` and `invitation` have no `owner_id` and no RLS: only `auth` uses `invitation`, and other modules read `owner` only where the table below says. Nor do the passkey tables, which Spring Security reads to learn who is signing in. They are tied to their owner by foreign keys that cascade: `user_entities.owner_id`, which a trigger fills in from `name`, the owner's email, as Spring Security writes it without one, and `user_credentials.user_entity_user_id`. `auth` joins `user_entities` by `owner_id` in every query.

## Tables by module

**Tables by module.** Spring Modulith checks only Java dependencies; SQL crosses modules in the places below, all reads for display or a daily job, except `preferences` saving an owner's language. Anything else goes through the owning module's public types.

| Table | Owner | Also used in SQL by |
|-------|-------|---------------------|
| `owner` | `auth` (registration inserts it) | `admin`: lists owners; `preferences`: the owner's language, read for every page and saved; `audit`, `links`, `locks`, `nuki`: owner ids for their daily jobs; `notifications`: the owner's email |
| `user_entities`, `user_credentials` | `auth` (Spring Security's passkey store) | none (deleting an owner cascades to them) |
| `invitation` | `auth` | none (`admin` goes through `InvitationService`) |
| `nuki_connection` | `nuki` | none |
| `lock` | `locks` | `links`: lock names in link lists; `audit`: names and time zones in the history and the home page's attempts; `notifications`: name and time zone; `resolve_link` |
| `access_link` | `links` | `audit`: labels in the history and the activity; `notifications`: labels, and dates for the email about a link; `access`: only through `resolve_link` |
| `access_link_schedule` | `links` | none (`access` goes through `LinkSchedules`) |
| `access_event` | `audit` | none |
| `owner_activity` | `audit` | none |
| `notification_preference` | `notifications` | none |

## Roles in Postgres

**Roles in Postgres**: `migrator` owns the schema and runs Liquibase; the app connects as `app_user`, with no ownership and no `BYPASSRLS`; the `backup` service and the off-site backup tool read as `backup_reader`, with `BYPASSRLS` and `pg_read_all_data` and no other grant. No `FORCE ROW LEVEL SECURITY`, so that `resolve_link` (owned by `migrator`) sees the link. Roles come from `deploy/db-init/01-create-roles.sh`, used by development, tests and production alike.

## Rules

- **Liquibase**: XML with native tags; `<sql>` only for CHECK constraints, RLS, GRANTs and functions. A new table with `owner_id` gets `ENABLE ROW LEVEL SECURITY`, its policy and its grants to `app_user` in the same changeset (`MigrationTests` fails otherwise).
- **Data access**: `JdbcClient` with records, only in classes named `*Dao`, which `DataAccessTests` checks; owner data only inside `OwnerTransactions.execute/run`, with `owner_id` in the query as well. No `findById(id)` without an owner.

## Decisions

| Decision | Why |
|----------|-----|
| Row-Level Security plus `owner_id` in every query | Isolation does not depend on every query being right. `OwnerIsolationTests` checks every route with an id. |
| AEAD with the owner id as associated data | A ciphertext copied to another owner's row does not decrypt. |
| Postgres's Alpine image (`postgres:18-alpine`) in production, development and tests, since 2026-11 | The other stacks on the server run it, so the Debian image (457 MB there) was this stack's alone (the user's choice, 2026-11-05). Alpine's musl compares text byte by byte, not as glibc's `en_US.utf8` did: lists ordered by name put capitals first and accented letters last, and moving a database from one image to the other needs its text indexes rebuilt, or lookups by email or passkey miss rows without an error (runbook, "Move the database to the Alpine image", tried on a copy of the stack). `backup` keeps uid 999, which owns `backups/` on servers set up before. |

## Tests

- **Isolation**: `OwnerIsolationTests` (every id route answers 404 for another owner and changes nothing), `MigrationTests` (every `owner_id` table has RLS and its policy), `RowLevelSecurityTests` (what `app_user` sees with and without an owner context), `ModularityTests` (module boundaries), `AdminPanelTests` (admins see no locks or links).
