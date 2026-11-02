# Runbook

How to run welcome-home in production. It runs as a Dockge stack on a server of your own, from the files in `deploy/` (see [deployment](deployment.md) for what each service does). Commands run as root in the stack's folder.

| What               | Where                                                                  |
|--------------------|------------------------------------------------------------------------|
| Public URL         | yours; `https://welcome-home.example.com` stands for it here           |
| Image              | `ghcr.io/jfisbein/welcome-home:v2`, the latest 2.x release             |
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
| Nuki's OAuth2 client, granted with its Advanced API | Owners connect by pasting an API token: the files `nuki-oauth-client-id` and `-secret` stay empty |
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

   The client id and secret are on Nuki Web's API page, under Advanced API, which Nuki has to grant first. Both files must exist, as `compose.yaml` asks for them: leave them empty if you have no client, and owners connect Nuki by pasting an API token instead.

5. **Start.** Deploy the stack. `db`, `app` and `backup` turn healthy within a minute or two, and `cloudflared` starts once `app` is. `https://welcome-home.example.com/actuator/health` answers `{"status":"UP"}` from outside.
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

## Nuki's webhook URL

Owners connected with OAuth2 learn at once whether the door opened when Nuki reports it with a webhook; without one, the lock's log confirms it a few seconds later, as before 2.5.0. The URL is the OAuth2 client's, set once in Nuki Web, after a release with `/nuki/webhook` (2.5.0 on) runs:

1. In Nuki Web, API → Advanced API, set the webhook URL to `https://welcome-home.example.com/nuki/webhook`. The app needs none of the device events: untick them all. Turning webhooks on makes Nuki send a test webhook, `{}`, which the app answers with 204, logging "Nuki sent a webhook that reports no action".
2. The same tab lists Nuki's last 300 webhooks with the status the app answered: 204 is right. 401 means that `secrets/nuki-oauth-client-secret` is not the client secret shown there; the app logs "Refused a webhook that Nuki did not sign".
3. An owner connected with OAuth2 before 2.5.1 connects again: at `/settings/nuki`, "Desconectar Nuki" and then "Conectar con Nuki" (their links do not open meanwhile). Their tokens lack the `webhook.central` scope, and Nuki refuses their advanced unlatches, which the app then replaces with the plain action, logging "Nuki refused to unlatch smartlock ... with its Advanced API: 401".
4. Nuki turns a client's webhooks off after several days of failing more than 5 % of them in a day, and emails the account first. Fix the cause, then reactivate them in the same tab.

## Release and upgrade

A release is a version tag on `main`; the workflow tests it, publishes the image `vX.Y.Z` (plus `vX` and `latest`) and creates the GitHub release, whose notes come from the release pull request.

```sh
git switch main && git pull
git tag vX.Y.Z && git push origin vX.Y.Z
```

Once the workflow has finished, on the server:

1. In Dockge, update the stack: it pulls `:v2` and `cloudflared:latest` again and recreates what changed.
2. Liquibase migrates the database as `app` starts. Check that `app` turns healthy and that `docker compose logs app` shows no errors. The release that runs:

   ```sh
   docker inspect welcome-home-app-1 --format '{{ index .Config.Labels "org.opencontainers.image.version" }}'
   ```

No dump first. Within a major version migrations only add to the schema, and each runs in a transaction: a migration that fails leaves the database as it was, and the previous release keeps working with one that succeeds (`MigrationCompatibilityTests` checks it). The `backup` service's nightly dump is still there.

A new major version, such as `v3`, may break compatibility: read its release notes, take a dump first (`docker compose exec backup /backup/backup.sh now`), and change the `app` image to `:v3`, with whatever else the notes ask for.

### From 1.x to 2.0

2.0.0 lets owners connect Nuki with OAuth2, and renames `nuki_connection.api_token_encrypted` to `access_token_encrypted`, which 1.x releases cannot read.

1. Take a dump: `docker compose exec backup /backup/backup.sh now`.
2. Check that `secrets/nuki-oauth-client-id` and `secrets/nuki-oauth-client-secret` hold the client id and secret from Nuki Web's API page (`chmod 444`, like the other secrets).
3. In Dockge, edit the stack as `deploy/compose.yaml` now has it: the `app` image `:v2`, and the two secrets, under `app`'s `secrets:` and in the file's `secrets:`. Deploy.
4. Check that `app` turns healthy and runs 2.0.0, and that `/settings/nuki` offers "Conectar con Nuki". An owner connected with an API token stays connected.

## Roll back

Pin the previous release, such as `ghcr.io/jfisbein/welcome-home:v2.0.0`, and deploy. Within a major version the older release works with the newer schema, so nothing else is needed; go back to `:v2` once a fixed release is out. **One thing to weigh first:** a release before 2.6.0 knows nothing of links' schedules, and those links open at any time within their dates. Before going back that far, revoke the links that have a schedule, or accept that; `SELECT DISTINCT link_id FROM access_link_schedule` lists them. Rolling back to an earlier major version means restoring the dump taken before upgrading (next section), and pinning that major's last release, such as `:v1.12.0`.

## Restore a dump

```sh
ls -lt backups/                                   # the newest dumps first
./restore.sh backups/welcome_home-YYYYMMDD-HHMMSS.dump
```

Run it as root: `backups/` is readable only by root and the `backup` service's user. The script stops `app` and `backup`, replaces the database with the dump and starts both again. Whatever happened after the dump is lost: owners, links and history alike.

A dump taken before `backup_reader` existed brings the database back without its `CONNECT` grant, and Databasus then fails to connect: run that `GRANT CONNECT` from "First start" again. Not while Databasus is dumping: `pg_restore` cannot drop a database that someone is connected to, and stops before changing anything. To start from Databasus's copy in B2, such as on a new server, download the backup in Databasus (Download, a `pg_dump` custom archive) and pass its path to `restore.sh`.

**On a new server:** follow "First start" with the keysets from your password manager (new passwords and a new tunnel token are fine), but do not deploy the whole stack yet: start only `db` (`docker compose up -d db`), so that it creates the roles, then run `restore.sh`, then deploy the rest. `backup_reader` must be among those roles, as the dumps carry its `CONNECT` grant and `pg_restore --create` stops without it; with a new password, change it in Databasus too. Without the keysets the dump still restores, but every owner has to paste their Nuki API token again and every access link has to be regenerated.

CI tries `backup.sh` and `restore.sh` on every change, on a copy of the stack with test data (`.github/scripts/test_backup_restore.sh`). That says that the scripts work, not that your dumps do, so still rehearse a restore of a real dump now and then, on a copy of the stack under another project name (`COMPOSE_PROJECT_NAME=welcome-home-test`) on any Docker host.

## Upgrade Postgres to a new major version

A new major version (17 → 18, later 19) cannot read the old one's data files: the data goes over as a dump. As root, in the stack's folder:

1. `docker compose exec backup /backup/backup.sh now`, and note the dump it prints.
2. `docker compose down`, then delete the old data: `docker volume rm welcome-home_db-data`. The dump in `backups/` (and in B2) is now the only copy.
3. Deploy the new `compose.yaml` in Dockge, but start only `db`: `docker compose up -d db`. It creates the roles on the empty volume.
4. `./restore.sh backups/<the dump>`: it restores the data and starts `app` and `backup`.
5. `docker compose up -d`, for `cloudflared`.

Since Postgres 18 the image keeps its data in `/var/lib/postgresql/<major>/docker`, so the volume is mounted at `/var/lib/postgresql`; an 18 image refuses to start on a volume mounted at the old `/var/lib/postgresql/data`.

## Rotate the AEAD keyset

The AEAD keyset encrypts the owners' Nuki tokens: pasted API tokens, and OAuth2 access and refresh tokens. Rotating it adds a new primary key: tokens stored from then on use it, and the earlier keys stay in the keyset to decrypt the tokens already stored.

1. Copy `aead-keyset.json` from your password manager to a development machine and run `./gradlew rotateAeadKeyset --args=<path to aead-keyset.json>`. It prints the new primary key's id.
2. Store the rotated file in your password manager, replacing the old one.
3. On the server, replace `secrets/aead-keyset.json` with it (`chmod 444`) and restart `app`: `docker compose restart app`. Delete the copy on the development machine.

Tokens stay encrypted with the key they were stored with until they are stored again. OAuth2 tokens are, on the first refresh after the rotation, within a day at most as the daily check refreshes them; a pasted API token only when its owner pastes it again. A disconnected connection is not refreshed. To see how many connections each key encrypts (the id is in the ciphertext, after a version byte):

```sh
docker compose exec db psql -U postgres -d welcome_home -c "
  SELECT ('x' || encode(substring(access_token_encrypted FROM 2 FOR 4), 'hex'))::bit(32)::bigint AS key_id, count(*)
  FROM nuki_connection GROUP BY 1"
```

A key that no token uses can be removed from the keyset (`jq 'del(.key[] | select(.keyId == <id>))'`), then stored and deployed as above. Removing a key that tokens still use leaves those owners without a working connection: they have to connect Nuki again.

**If the AEAD keyset leaked**, together with a dump the owners' tokens are exposed. Rotate the keyset, then ask every owner to replace theirs:

- Connected with an API token: delete it in Nuki Web, and paste a new one.
- Connected with OAuth2: at `/settings/nuki`, "Desconectar Nuki", which asks Nuki to revoke the authorization (check the log for "Asked Nuki to revoke"), and then "Conectar con Nuki". Using a stolen refresh token also takes the client secret: if that may have leaked too, replace it in Nuki Web and in `secrets/nuki-oauth-client-secret`.

Once the old key encrypts no token, remove it.

**If the MAC keyset leaked**, anyone who knows a link's id can forge its URL. Replace it with a new one (`./gradlew generateKeysets` into an empty folder, keeping only `mac-keyset.json`), store it in your password manager and on the server, and restart `app`: every access link changes, and owners have to share their links again.

## An owner leaves

An admin deletes them in `/admin/owners` ("Dar de baja"): their account, passkeys, Nuki connection, locks, links and history go at once. Their data stays in the dumps, locally and in B2, until the dumps expire after 30 days. An OAuth2 authorization is revoked at Nuki as they go (the log says "Asked Nuki to revoke", or "Could not revoke"); an API token is theirs to delete in Nuki Web, so remind them.

## A leaked access link

The owner, on the lock's links page:

- **Cambiar enlace**, to keep the link for the same guest: the leaked URL stops working at once and the page shows a new one, with the same dates.
- **Anular**, to end it: from then on it opens nothing.

The link's **Historial** shows whether, when and from which IP address it was used.

## When something fails

| Symptom | Where to look |
|---------|---------------|
| The site does not load | `docker compose ps`: `cloudflared` running and `app` healthy? `docker compose logs cloudflared` for the tunnel, and the tunnel's status in Cloudflare Zero Trust. |
| `app` does not turn healthy | `docker compose logs app`: a missing or unreadable keyset or secret, or the database, is named in the error. |
| `backup` restarting, "/backups is not writable by uid 999" | `backups/` did not exist before the first start, so Docker created it for root: `chown 999:999 backups && chmod 700 backups`, then `docker compose restart backup`. |
| `backup` unhealthy | No dump in the last 25 hours: `docker compose logs backup`, and check that the disk has room. |
| Guests see "Esta puerta no está disponible ahora mismo" | The lock is inactive, or the owner's Nuki connection is disconnected: the owner's home page says which. Connecting Nuki again at `/settings/nuki` fixes the latter, and a connection that Nuki accepts again comes back by itself within the hour, for 7 days. |
| Sliding to open fails for every guest | Cloudflare's Bot Fight Mode or a WAF rule may be challenging `POST /api/open`: check Security → Events in Cloudflare. Otherwise `docker compose logs app` shows what Nuki answered. |
| Openings take seconds to confirm for owners connected with OAuth2 | Nuki's webhooks are not arriving: see "Nuki's webhook URL" above. `docker compose logs app` shows "Nuki reported request ..." for each one that does. |
| An owner lost their passkey | An admin creates a passkey recovery link for them in `/admin/owners` ("Nueva passkey") and sends it; the owner keeps their other passkeys, and then deletes the lost device's one in Configuración → Passkeys. If the one who lost it is the only admin: `docker compose run --rm app --recover-owner=<email>` on the server. A passkey kept in a synced password manager (such as Vaultwarden) survives a lost device. |
