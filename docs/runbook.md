# Runbook

How to run welcome-home in production. It runs as a Dockge stack on a server of your own, from the files in `deploy/` (see [deployment](deployment.md) for what each service does). Commands run as root in the stack's folder.

| What               | Where                                                                  |
|--------------------|------------------------------------------------------------------------|
| Public URL         | yours; `https://welcome-home.example.com` stands for it here           |
| Image              | `ghcr.io/jfisbein/welcome-home:v4`, the latest 4.x release             |
| Secrets            | `secrets/` in the stack's folder; the keysets also in a password manager |
| Database dumps     | `backups/` in the stack's folder; off-site, wherever your backup tool puts them |
| Tunnel             | Cloudflare Zero Trust: its own tunnel, with only this public hostname  |

What the stack needs, and what is up to you. The names in brackets are what this runbook uses as examples; any other does as well.

| Needed | What for |
|---|---|
| A Docker host with Compose, such as a Dockge stack | Runs the four services |
| A Cloudflare tunnel | The only way in, which the app's trust in Cloudflare's headers relies on |
| A safe place for the keysets, away from the dumps (Vaultwarden) | Without them, a dump cannot decrypt Nuki tokens or keep links working |

| Optional | Without it |
|---|---|
| An SMTP server (SMTP2GO) | No emails: remove `SPRING_MAIL_HOST` from `compose.yaml`, and leave the files `smtp_username` and `smtp_password` empty, as it still asks for them. The pages then say that emails are off |
| Nuki's OAuth2 client, granted with its Advanced API | Nobody can connect Nuki: it is the only way to. Ask Nuki for it first (Nuki Web → API → Advanced API), which takes days |
| An off-site backup tool (Databasus, dumping to Backblaze B2) | Dumps stay only in `backups/` on the server, lost with it |

## First start

1. **Tunnel.** In Cloudflare Zero Trust, create a tunnel for welcome-home alone, with the public hostname `welcome-home.example.com` pointing to `http://app:8080`, and copy its token.
2. **Keysets.** On a development machine, `./gradlew generateKeysets` writes them to `secrets/` (production's own: never the development ones). Store `aead-keyset.json` and `mac-keyset.json` in your password manager, then delete them from the development machine once they are on the server.
3. **Stack.** Create the stack in Dockge from `deploy/compose.yaml`, and copy `deploy/db-init/`, `deploy/backup/` and `deploy/restore.sh` next to it.
4. **Secrets.** In the stack's folder:

   ```sh
   mkdir secrets && chmod 700 secrets
   for name in postgres_password migrator_password app_user_password backup_reader_password; do
       openssl rand -base64 24 | tr -d '\n' > secrets/$name
   done
   # the two keysets, as secrets/aead-keyset.json and secrets/mac-keyset.json
   printf '%s' '<tunnel token>' > secrets/tunnel_token
   printf '%s' '<SMTP user>' > secrets/smtp_username         # empty without an SMTP server
   printf '%s' '<SMTP password>' > secrets/smtp_password
   printf '%s' '<client id>' > secrets/nuki-oauth-client-id
   printf '%s' '<client secret>' > secrets/nuki-oauth-client-secret
   chmod 444 secrets/*
   install -d -o 999 -g 999 -m 700 backups
   ```

   The client id and secret are on Nuki Web's API page, under Advanced API, which Nuki has to grant first. Both files must exist, as `compose.yaml` asks for them: with them empty the app starts, and its Nuki page says that this install cannot connect Nuki yet.

5. **Start.** Deploy the stack. `db`, `app` and `backup` turn healthy within a minute or two, and `cloudflared` starts once `app` is, and turns healthy once the tunnel connects. `https://welcome-home.example.com/actuator/health` answers `{"status":"UP"}` from outside.
6. **First admin.** `docker compose run --rm app --bootstrap-admin` prints an invitation link, valid for 72 hours. Open it on your phone or computer, enter your email and create the passkey. Further owners are invited from `/admin/owners`.
7. **Emails**, optional. With SMTP2GO, for example: verify the sender domain `example.com` (its DNS records) and create an SMTP user; its name and password are `secrets/smtp_username` and `smtp_password`. The server is `SPRING_MAIL_HOST` and `SPRING_MAIL_PORT` in `compose.yaml`, and the sender `NOTIFICATIONS_FROM`. Check with "Enviarme un email de prueba" at `/settings/notifications`.
8. **Off-site copy**, optional but recommended. Any tool that can reach the database over `dbbackup` as `backup_reader` will do. With Databasus, for example, a stack on the same Docker host that dumps the database to B2 every day:
   1. In Databasus's `compose.yaml`, join the network that `db` shares with it, which this stack creates on every start:

      ```yaml
      services:
        databasus:
          networks:
            - default
            - welcome-home_dbbackup
      networks:
        welcome-home_dbbackup:
          external: true
      ```

   2. On a database created before `backup_reader` existed, `db-init` did not create it: create it by hand, as root in the stack's folder, with `docker compose exec db psql -U postgres -d welcome_home` and the password in `secrets/backup_reader_password`:

      ```sql
      CREATE ROLE backup_reader LOGIN BYPASSRLS PASSWORD '<secrets/backup_reader_password>';
      GRANT CONNECT ON DATABASE welcome_home TO backup_reader;
      GRANT pg_read_all_data TO backup_reader;
      ```

   3. In Databasus, add a PostgreSQL database: host `welcome-home-db-1`, port 5432, database `welcome_home`, user `backup_reader` with the password in `secrets/backup_reader_password`. Databasus lists `BYPASSRLS` among the role's privileges and offers no read-only user of its own instead, because of Row-Level Security: that is right. A daily backup after the access events' purge at 03:30, such as 04:15; **keep 30 days at most**, and check that the bucket deletes what Databasus deletes rather than keeping old versions, so that 30 days after an owner is deleted no copy of their data is left.

   While Databasus is attached, `docker compose down` here reports `Resource is still in use` for `welcome-home_dbbackup`: the network stays, and the next start uses it again.

## Watch it from outside

Nothing in the stack tells anyone that it broke: the health checks show only in Dockge, and Docker does not restart a container that turns unhealthy. A guest at the door would be the first to know. So have a service outside the server ask for it (review item OPS-01):

- **What to ask.** `GET https://welcome-home.example.com/actuator/health`, every few minutes: it answers 200 with `"status":"UP"` while the app is up and reaches its database, and tells nothing else. Alert after two failures in a row, by email or whatever reaches you away from home.
- **From outside.** A monitor on the same server, or the same network, goes down with what it watches. From outside it also covers the tunnel, the server and the domain.
- **Through Cloudflare.** A rule that blocks countries, or Bot Fight Mode, stops a monitor that asks from elsewhere with a 403, which reads as the app being down: let that one path of that one host through, in the rule that skips the others.
- **What it does not cover.** A backup that stopped and a nightly job that fails leave the app healthy. `ls -lt backups/ | head` now and then is all there is for the first; the app's log for the second.

## Release and upgrade

A release is a version tag on `main`; the workflow tests it, publishes the image `vX.Y.Z` (plus `vX` and `latest`) and creates the GitHub release, whose notes come from the release pull request.

```sh
git switch main && git pull
git tag vX.Y.Z && git push origin vX.Y.Z
```

Once the workflow has finished, on the server:

1. In Dockge, update the stack: it pulls `:v4` and `cloudflared:latest` again and recreates what changed.
2. The `migrate` service migrates the database and ends, and then `app` starts. Check that `app` turns healthy and that `docker compose logs migrate app` shows no errors; if `app` did not start, `migrate` ended in an error, and its log says which changeset. The release that runs:

   ```sh
   docker inspect welcome-home-app-1 --format '{{ index .Config.Labels "org.opencontainers.image.version" }}'
   ```

   The menu of the owners' pages shows it too, beside the app's name.

No dump first. Within a major version migrations only add to the schema, and each runs in a transaction: a migration that fails leaves the database as it was, and the previous release keeps working with one that succeeds (`MigrationCompatibilityTests` checks it). The `backup` service's nightly dump is still there, and `docker compose exec backup /backup/backup.sh now` takes one in a few seconds for whoever would rather have it: it costs nothing and is not required.

A new major version, such as `v5`, may break compatibility: read its release notes, take a dump first (`docker compose exec backup /backup/backup.sh now`), and change the `app` image to `:v5`, with whatever else the notes ask for.

### From 3.x to 4.0

4.0.0 knows an owner by their id, not by their email, in their session and in the passkey store (changeset 041), and drops three columns nothing read (040). No 3.x release runs against that schema. And migrations move out of `app` into a service of their own, so that `app` no longer has the password of the role that owns the tables.

1. Take a dump: `docker compose exec backup /backup/backup.sh now`. It is the only way back to 3.x.
2. In Dockge, edit the stack as `deploy/compose.yaml` now has it:
   - add the `migrate` service, whole;
   - in `app`: the image `:v4`; under `depends_on`, `migrate` with `condition: service_completed_successfully`; under `environment`, `SPRING_LIQUIBASE_ENABLED: "false"` in place of `SPRING_LIQUIBASE_USER`; under `secrets`, take out the two lines of `migrator_password`.

   Deploy. A stack left as it was, with only the image changed, still works: `app` then migrates as it starts, and keeps the password.
3. Check that `app` turns healthy and runs 4.0.0 (the command above), and sign in with a passkey you had: passkeys made before go on working, and every owner signs in again once, as sessions do not outlive a restart.

### From 2.x to 3.0

3.0.0 unlatches one way and connects Nuki one way. Until 2.18.0, owners connected with OAuth2 unlatched through Nuki's Advanced API, which reported each outcome to `/nuki/webhook`, and an owner could connect by pasting an API token instead: both are gone, with `nuki_connection.credential` (changeset 039), which no 2.x release runs without.

1. Take a dump: `docker compose exec backup /backup/backup.sh now`. It is the only way back to 2.x.
2. In Dockge, edit the stack: the `app` image `:v3`. Nothing else in `compose.yaml` changes. Deploy.
3. Check that `app` turns healthy and runs 3.0.0 (the command above).
4. In Nuki Web, API → Advanced API, clear the webhook URL: Nuki sends none without an advanced action, but would otherwise keep the address. If it does not let you clear it alone, deactivate the Advanced API there: the OAuth2 client, and so connecting and every connection, goes on working without it. Do not delete the integration.
5. In Cloudflare, delete the IP Access rule that allowed Nuki's address past Bot Fight Mode (Security → WAF → Tools): nothing from Nuki reaches the app any more.
6. An owner who had pasted an API token, which the app took until 2.18.0, is no longer connected: the upgrade deleted that connection, and they connect at `/settings/nuki` with "Conectar con Nuki". The token itself stays valid at Nuki until they delete it in Nuki Web (API → API tokens).
7. Each owner connected with OAuth2 connects again, at `/settings/nuki`, "Desconectar Nuki" and then "Conectar con Nuki" (their links do not open meanwhile): the tokens they have still carry `smartlock.auth` and `webhook.central`, which the app no longer asks for. Their openings work either way.
8. Open a door with a link, in front of it, to see that all of it works together.

### From 1.x to 2.0

2.0.0 lets owners connect Nuki with OAuth2, and renames `nuki_connection.api_token_encrypted` to `access_token_encrypted`, which 1.x releases cannot read.

1. Take a dump: `docker compose exec backup /backup/backup.sh now`.
2. Check that `secrets/nuki-oauth-client-id` and `secrets/nuki-oauth-client-secret` hold the client id and secret from Nuki Web's API page (`chmod 444`, like the other secrets).
3. In Dockge, edit the stack as `deploy/compose.yaml` now has it: the `app` image `:v2`, and the two secrets, under `app`'s `secrets:` and in the file's `secrets:`. Deploy.
4. Check that `app` turns healthy and runs 2.0.0, and that `/settings/nuki` offers "Conectar con Nuki". An owner connected with an API token stays connected.

## Roll back

Pin the previous release of the same major version, such as `ghcr.io/jfisbein/welcome-home:v4.0.0`, and deploy. Within a major version the older release works with the newer schema, so nothing else is needed; go back to `:v4` once a fixed release is out. Before pinning a release that is several behind, read the notes of those in between for what it does not know: it ignores the columns added since, and so whatever they turned on.

Rolling back to an earlier major version means restoring the dump taken before upgrading (next section), and pinning that major's last release, `:v3.0.0`, `:v2.18.0` or `:v1.12.0`: no release of an earlier major runs against a later one's schema.

## Restore a dump

```sh
ls -lt backups/                                   # the newest dumps first
./restore.sh backups/welcome_home-YYYYMMDD-HHMMSS.dump
```

Run it as root: `backups/` is readable only by root and the `backup` service's user. The script checks that the file is a dump before it touches anything, stops `app` and `backup`, dumps what the database holds to `backups/`, replaces the database with the dump and starts both again. Whatever happened after the dump you restore is no longer in the database, owners, links and history alike, but it is in the dump the script has just taken, whose name it prints: restore that one to go back. If that dump cannot be taken, as with a database that no longer reads, the script stops with the database untouched; `SKIP_DUMP=1 ./restore.sh <dump>` goes on without it, and `docker compose up --detach` starts `app` and `backup` again instead.

The script creates the database itself, as the migrator's and with who may connect to it (`app_user`, and `backup_reader` if the role exists), and then restores the dump into it, so a dump from before `backup_reader` existed needs nothing more. The database it creates compares text by PostgreSQL's own rules, whatever the dump's did (see "Move the database to PostgreSQL's own collation"). Not while Databasus is dumping: a database that someone is connected to cannot be dropped, and the script stops before changing anything. To start from Databasus's copy in B2, such as on a new server, download the backup in Databasus (Download, a `pg_dump` custom archive) and pass its path to `restore.sh`.

**On a new server:** follow "First start" with the keysets from your password manager (new passwords and a new tunnel token are fine), but do not deploy the whole stack yet: start only `db` (`docker compose up -d db`), so that it creates the roles, then run `restore.sh`, then deploy the rest. `backup_reader` must be among those roles, as the dumps carry its grants and `pg_restore` stops without it; with a new password, change it in Databasus too. Without the keysets the dump still restores, but every owner has to paste their Nuki API token again and every access link has to be regenerated.

CI tries `backup.sh` and `restore.sh` on every change, on a copy of the stack with test data (`.github/scripts/test_backup_restore.sh`). That says that the scripts work, not that your dumps do, so still rehearse a restore of a real dump now and then, on a copy of the stack under another project name (`COMPOSE_PROJECT_NAME=welcome-home-test`) on any Docker host.

## Upgrade Postgres to a new major version

A new major version (17 → 18, later 19) cannot read the old one's data files: the data goes over as a dump. As root, in the stack's folder:

1. `docker compose exec backup /backup/backup.sh now`, and note the dump it prints.
2. `docker compose down`, then delete the old data: `docker volume rm welcome-home_db-data`. The dump in `backups/` (and in B2) is now the only copy.
3. Deploy the new `compose.yaml` in Dockge, but start only `db`: `docker compose up -d db`. It creates the roles on the empty volume.
4. `./restore.sh backups/<the dump>`: it restores the data and starts `app` and `backup`.
5. `docker compose up -d`, for `cloudflared`.

Since Postgres 18 the image keeps its data in `/var/lib/postgresql/<major>/docker`, so the volume is mounted at `/var/lib/postgresql`; an 18 image refuses to start on a volume mounted at the old `/var/lib/postgresql/data`.

## Move the database to the Alpine image

Until 2026-11 the stack ran Postgres's Debian image (`postgres:18`); `deploy/compose.yaml` now has the Alpine one (`postgres:18-alpine`), which the other stacks on the server use. The data files are the same, but Debian compares text with glibc and Alpine with musl, which compares it byte by byte: the indexes on text, owners' emails and passkeys among them, were built in glibc's order, and until they are rebuilt Postgres misses rows they hold, without any error: an owner's passkey may not be found, and they cannot sign in. So, once, as root in the stack's folder:

1. Copy `deploy/backup/backup.sh` over `backup/backup.sh`: the earlier one cannot schedule its dumps with Alpine's `date`, and the new one works with either image.
2. `docker compose exec backup /backup/backup.sh now`, and note the dump it prints.
3. `docker compose stop app`: nothing may use the indexes until they are rebuilt.
4. In Dockge, set `image: postgres:18-alpine` for `db` and `backup`, as `deploy/compose.yaml` has them, and save it without deploying; then `docker compose up -d db backup`. The image's entrypoint gives the data files to its `postgres` user (uid 70 rather than 999); `backups/` stays 999's, as the `backup` service still runs as 999.
5. Rebuild the indexes, and forget the glibc version that each database recorded, which musl cannot give, as otherwise every connection logs `has no actual collation version, but a version was recorded` (`ALTER DATABASE ... REFRESH COLLATION VERSION` refuses to clear it):

   ```sh
   docker compose exec db psql -U postgres -d welcome_home -c 'REINDEX DATABASE welcome_home'
   docker compose exec db psql -U postgres -d postgres -c 'UPDATE pg_database SET datcollversion = NULL WHERE datcollversion IS NOT NULL'
   ```

6. `docker compose up -d`, check that `app` turns healthy and that you can sign in, and, after its 04:00, that `backup` logged a dump.
7. `docker image rm postgres:18` and `docker image prune`, now that no stack uses them.

Going back is the same steps with `postgres:18`, the REINDEX included. Lists ordered by name, such as the locks, now put capitals before lower case and accented letters after `z`. Development's database (`compose.yaml`) moves the same way: `docker compose up -d`, then step 5 against it.

## Move the database to PostgreSQL's own collation

A database created before 2026-11-19 compares text by the image's C library, which is why moving to Alpine needed its indexes rebuilt (above), and why any later change of image, or of its C library's version, could again leave indexes that miss rows without an error. A database created since compares text by PostgreSQL's own rules (the built-in provider, `C.UTF-8`: by code point, as musl already does, so nothing changes in what owners see), which no image changes. A database's collation is set when it is created, so an existing one moves by a dump and a restore, which `restore.sh` does since that day. Nothing is urgent while the image stays as it is; do it before the next change of image, or let the next upgrade of Postgres do it, as that restores a dump anyway. As root, in the stack's folder:

1. Copy `deploy/restore.sh` over the stack's `restore.sh`.
2. `docker compose exec db psql -U postgres -tAc "SELECT datlocprovider FROM pg_database WHERE datname = 'welcome_home'"`: `c` is the C library, `b` PostgreSQL's own, and then there is nothing to do.
3. `docker compose exec backup /backup/backup.sh now`, and note the dump it prints.
4. `./restore.sh backups/<the dump>`: it stops `app` and `backup`, creates the database anew, restores the dump into it and starts both. Whatever happened between the dump and the restore is lost, so do the two together, when nobody is expected at a door.
5. Step 2 again, which now says `b`; check that `app` is healthy and that you can sign in.

The server's other databases (`postgres`, the templates) stay as they were, which does not matter: nothing of the app's is in them. A volume created from `deploy/compose.yaml` since that day has every database so (`POSTGRES_INITDB_ARGS`). Development's database moves by `docker compose down -v` and starting again.

## Rotate the AEAD keyset

The AEAD keyset encrypts the owners' Nuki tokens (OAuth2 access and refresh tokens) and their links' PINs. Rotating it adds a new primary key: what is stored from then on uses it, and the earlier keys stay in the keyset to decrypt what is already stored.

1. Copy `aead-keyset.json` from your password manager to a development machine and run `./gradlew rotateAeadKeyset --args=<path to aead-keyset.json>`. It prints the new primary key's id.
2. Store the rotated file in your password manager, replacing the old one.
3. On the server, replace `secrets/aead-keyset.json` with it (`chmod 444`) and restart `app`: `docker compose restart app`. Delete the copy on the development machine.

On starting, the app encrypts again with the new key every stored token that an earlier key encrypted, whatever its connection's status, and logs `Nuki tokens encrypted again with key <id>: <count>` and then `Every stored Nuki token is encrypted with key <id>`. A token that does not decrypt, as when its key already left the keyset, is left as it is and named in a warning (`Could not decrypt the Nuki tokens of owner ...`): that owner has to connect Nuki again. It does the same for links' PINs (`Link PINs encrypted again with key <id>: <count>`, `Every stored link PIN is encrypted with key <id>`, and `Could not decrypt the link PINs of owner ...` for one whose owner must set it again). To see how much each key encrypts, in the three columns the keyset is for (the id is in the ciphertext, after a version byte):

```sh
docker compose exec db psql -U postgres -d welcome_home -c "
  SELECT what, ('x' || encode(substring(encrypted FROM 2 FOR 4), 'hex'))::bit(32)::bigint AS key_id, count(*)
  FROM (SELECT 'access token' AS what, access_token_encrypted AS encrypted FROM nuki_connection
        UNION ALL SELECT 'refresh token', refresh_token_encrypted FROM nuki_connection
        UNION ALL SELECT 'link PIN', pin_encrypted FROM access_link) AS stored
  WHERE encrypted IS NOT NULL GROUP BY 1, 2 ORDER BY 1, 2"
```

A key may leave the keyset only when none of the three shows it.

Keep the earlier key for 30 days, as long as the dumps taken before the rotation are kept: restoring one of them needs it, or the restored connections and PINs do not decrypt, and their owners have to connect Nuki again and set the PINs again. Then remove it from the keyset (`jq 'del(.key[] | select(.keyId == <id>))'`), and store and deploy the keyset as above. Removing a key that tokens or PINs still use leaves those owners without a working connection, and those links refusing every guest until their PIN is set again.

**If the AEAD keyset leaked**, together with a dump the owners' tokens are exposed. Rotate the keyset, then ask every owner to replace theirs:

- Connected with OAuth2: at `/settings/nuki`, "Desconectar Nuki", which asks Nuki to revoke the authorization (check the log for "Asked Nuki to revoke"), and then "Conectar con Nuki". Using a stolen refresh token also takes the client secret: if that may have leaked too, replace it in Nuki Web and in `secrets/nuki-oauth-client-secret`.

Remove the old key at once, without waiting the 30 days: the app has encrypted every token again on starting, and the dumps that need the old key are the ones whose tokens the owners are replacing.

**If the MAC keyset leaked**, anyone who knows a link's id can forge its URL. Replace it with a new one (`./gradlew generateKeysets` into an empty folder, keeping only `mac-keyset.json`), store it in your password manager and on the server, and restart `app`: every access link changes, and owners have to share their links again.

## An owner leaves

An admin deletes them in `/admin/owners` ("Dar de baja"): their account, passkeys, Nuki connection, locks, links and history go at once. Their data stays in the dumps, locally and in B2, until the dumps expire after 30 days. An OAuth2 authorization is revoked at Nuki as they go (the log says "Asked Nuki to revoke", or "Could not revoke"); if it could not, the owner can remove welcome-home's access in Nuki Web.

## A leaked access link

The owner, on the link's page, which its name in the lock's links leads to:

- **Sustituir enlace**, to keep the link for the same guest: the leaked URL stops working at once and the page shows a new one, with the same dates.
- **Anular**, to end it: from then on it opens nothing.

The link's **Historial** shows whether, when and from where it was used: the IP address for 90 days, the country after.

## When something fails

| Symptom | Where to look |
|---------|---------------|
| The site does not load | `docker compose ps`: `cloudflared` and `app` healthy? An unhealthy `cloudflared` has no connection to Cloudflare. `docker compose logs cloudflared` for the tunnel, and the tunnel's status in Cloudflare Zero Trust. |
| `app` does not turn healthy | `docker compose logs app`: a missing or unreadable keyset or secret, or the database, is named in the error. |
| `backup` restarting, "/backups is not writable by uid 999" | `backups/` did not exist before the first start, so Docker created it for root: `chown 999:999 backups && chmod 700 backups`, then `docker compose restart backup`. |
| `backup` unhealthy | No dump in the last 25 hours: `docker compose logs backup`, and check that the disk has room. |
| Guests see "Esta puerta no está disponible ahora mismo" | The lock is inactive, or the owner's Nuki connection is disconnected: the owner's home page says which. Connecting Nuki again at `/settings/nuki` fixes the latter, and a connection that Nuki accepts again comes back by itself within the hour, for 7 days. |
| Sliding to open fails for every guest | Cloudflare's Bot Fight Mode or a WAF rule may be challenging `POST /api/open`: check Security → Events in Cloudflare. Otherwise `docker compose logs app` shows what Nuki answered. |
| An owner lost their passkey | An admin creates a passkey recovery link for them in `/admin/owners` ("Nueva passkey") and sends it; the owner keeps their other passkeys, and then deletes the lost device's one in Configuración → Passkeys. If the one who lost it is the only admin: `docker compose run --rm app --recover-owner=<email>` on the server. A passkey kept in a synced password manager (such as Vaultwarden) survives a lost device. |
