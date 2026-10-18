# Runbook

How to run welcome-home in production. It runs as a Dockge stack on `docker.fritz.box`, from the files in `deploy/` (see "Production" in the [README](../README.md) for what each service does). Commands run as root in the stack's folder, `/opt/stacks/welcome-home` with Dockge's defaults.

| What               | Where                                                                  |
|--------------------|------------------------------------------------------------------------|
| Public URL         | `https://welcome-home.alcornoque.top`                                  |
| Image              | `ghcr.io/jfisbein/welcome-home`, the release named in `compose.yaml`   |
| Secrets            | `secrets/` in the stack's folder; the keysets also in Vaultwarden      |
| Database dumps     | `backups/` in the stack's folder, copied daily to B2 by zerobyte       |
| Tunnel             | Cloudflare Zero Trust: its own tunnel, with only this public hostname  |

## First start

1. **Tunnel.** In Cloudflare Zero Trust, create a tunnel for welcome-home alone, with the public hostname `welcome-home.alcornoque.top` pointing to `http://app:8080`, and copy its token. Remove the earlier route to `docker.fritz.box:7070`.
2. **Keysets.** On a development machine, `./gradlew generateKeysets` writes them to `secrets/` (production's own: never the development ones). Store `aead-keyset.json` and `mac-keyset.json` in Vaultwarden, then delete them from the development machine once they are on the server.
3. **Stack.** Create the stack in Dockge from `deploy/compose.yaml`, and copy `deploy/db-init/`, `deploy/backup/` and `deploy/restore.sh` next to it.
4. **Secrets.** In the stack's folder:

   ```sh
   mkdir secrets && chmod 700 secrets
   for name in postgres_password migrator_password app_user_password; do
       openssl rand -base64 24 | tr -d '\n' > secrets/$name
   done
   # the two keysets, as secrets/aead-keyset.json and secrets/mac-keyset.json
   printf '%s' '<tunnel token>' > secrets/tunnel_token
   chmod 444 secrets/*
   install -d -o 999 -g 999 -m 700 backups
   ```

5. **Start.** Deploy the stack. `db`, `app` and `backup` turn healthy within a minute or two, and `cloudflared` starts once `app` is. `https://welcome-home.alcornoque.top/actuator/health` answers `{"status":"UP"}` from outside.
6. **First admin.** `docker compose run --rm app --bootstrap-admin` prints an invitation link, valid for 72 hours. Open it on your phone or computer, enter your email and create the passkey. Further owners are invited from `/admin/owners`.
7. **Off-site copy.** In zerobyte, a daily job after 04:00 that backs up the stack's `backups/` folder to B2, keeping 30 days of snapshots.

## Release and upgrade

A release is a version tag on `main`; the workflow tests it and publishes the image `vX.Y.Z` (plus `vX` and `latest`).

```sh
git switch main && git pull
git tag vX.Y.Z && git push origin vX.Y.Z
```

Once the workflow has finished, on the server:

1. Take a dump, so that the upgrade can be undone: `docker compose exec backup /backup/backup.sh now`.
2. In Dockge, change the `app` image to `ghcr.io/jfisbein/welcome-home:vX.Y.Z` and deploy: Dockge pulls the image and recreates `app`. If `deploy/` changed in the release, bring those changes over too.
3. Liquibase migrates the database on start. Check that `app` turns healthy and that `docker compose logs app` shows no errors.

## Roll back

Change the image back to the previous release and deploy. If the newer release migrated the database (its log shows changesets run at start), the older one may not work with it: restore the dump taken before the upgrade (next section) after rolling back.

## Restore a dump

```sh
ls -lt backups/                                   # the newest dumps first
./restore.sh backups/welcome_home-YYYYMMDD-HHMMSS.dump
```

Run it as root: `backups/` is readable only by root and the `backup` service's user. The script stops `app` and `backup`, replaces the database with the dump and starts both again. Whatever happened after the dump is lost: owners, links and history alike.

To get a dump from B2, restore it with zerobyte into any folder and pass its path to `restore.sh`.

**On a new server:** follow "First start" with the keysets from Vaultwarden (new passwords and a new tunnel token are fine), but do not deploy the whole stack yet: start only `db` (`docker compose up -d db`), so that it creates the roles, then run `restore.sh`, then deploy the rest. Without the keysets the dump still restores, but every owner has to paste their Nuki API token again and every access link has to be regenerated.

Rehearse a restore now and then, on a copy of the stack under another project name (`COMPOSE_PROJECT_NAME=welcome-home-test`) on any Docker host, so that the procedure is known to work before it is needed.

## Upgrade Postgres to a new major version

A new major version (17 → 18, later 19) cannot read the old one's data files: the data goes over as a dump. As root, in the stack's folder:

1. `docker compose exec backup /backup/backup.sh now`, and note the dump it prints.
2. `docker compose down`, then delete the old data: `docker volume rm welcome-home_db-data`. The dump in `backups/` (and in B2) is now the only copy.
3. Deploy the new `compose.yaml` in Dockge, but start only `db`: `docker compose up -d db`. It creates the roles on the empty volume.
4. `./restore.sh backups/<the dump>`: it restores the data and starts `app` and `backup`.
5. `docker compose up -d`, for `cloudflared`.

Since Postgres 18 the image keeps its data in `/var/lib/postgresql/<major>/docker`, so the volume is mounted at `/var/lib/postgresql`; an 18 image refuses to start on a volume mounted at the old `/var/lib/postgresql/data`.

## Rotate the AEAD keyset

The AEAD keyset encrypts the owners' Nuki API tokens. Rotating it adds a new primary key: tokens stored from then on use it, and the earlier keys stay in the keyset to decrypt the tokens already stored.

1. Copy `aead-keyset.json` from Vaultwarden to a development machine and run `./gradlew rotateAeadKeyset --args=<path to aead-keyset.json>`. It prints the new primary key's id.
2. Store the rotated file in Vaultwarden, replacing the old one.
3. On the server, replace `secrets/aead-keyset.json` with it (`chmod 444`) and restart `app`: `docker compose restart app`. Delete the copy on the development machine.

Each owner's token stays encrypted with the key it was stored with until the owner pastes it again. To see how many tokens each key encrypts (the id is in the ciphertext, after a version byte):

```sh
docker compose exec db psql -U postgres -d welcome_home -c "
  SELECT ('x' || encode(substring(api_token_encrypted FROM 2 FOR 4), 'hex'))::bit(32)::bigint AS key_id, count(*)
  FROM nuki_connection GROUP BY 1"
```

A key that no token uses can be removed from the keyset (`jq 'del(.key[] | select(.keyId == <id>))'`), then stored and deployed as above. Removing a key that tokens still use disconnects those owners: they have to paste their token again.

**If the AEAD keyset leaked**, together with a dump the owners' tokens are exposed. Rotate the keyset, ask every owner to revoke their API token in Nuki Web and paste a new one, and once the old key encrypts no token, remove it.

**If the MAC keyset leaked**, anyone who knows a link's id can forge its URL. Replace it with a new one (`./gradlew generateKeysets` into an empty folder, keeping only `mac-keyset.json`), store it in Vaultwarden and on the server, and restart `app`: every access link changes, and owners have to share their links again.

## A leaked access link

The owner, on the lock's links page:

- **Regenerar**, to keep the link for the same guest: the leaked URL stops working at once and the page shows a new one, with the same dates.
- **Revocar**, to end it: from then on it opens nothing.

The link's **Historial** shows whether, when and from which IP address it was used.

## When something fails

| Symptom | Where to look |
|---------|---------------|
| The site does not load | `docker compose ps`: `cloudflared` running and `app` healthy? `docker compose logs cloudflared` for the tunnel, and the tunnel's status in Cloudflare Zero Trust. |
| `app` does not turn healthy | `docker compose logs app`: a missing or unreadable keyset or secret, or the database, is named in the error. |
| `backup` restarting, "/backups is not writable by uid 999" | `backups/` did not exist before the first start, so Docker created it for root: `chown 999:999 backups && chmod 700 backups`, then `docker compose restart backup`. |
| `backup` unhealthy | No dump in the last 25 hours: `docker compose logs backup`, and check that the disk has room. |
| Guests see "Esta puerta no está disponible ahora mismo" | The lock is inactive, or the owner's Nuki connection is disconnected: the owner's panel says which, and pasting a new API token fixes the latter. |
| "Abrir" fails for every guest | Cloudflare's Bot Fight Mode or a WAF rule may be challenging `POST /api/open`: check Security → Events in Cloudflare. Otherwise `docker compose logs app` shows what Nuki answered. |
| An owner lost their passkey | An admin creates a passkey recovery link for them in `/admin/owners` ("Nueva passkey") and sends it; the owner keeps their other passkeys. If the one who lost it is the only admin: `docker compose run --rm app --recover-owner=<email>` on the server. A passkey kept in a synced password manager (such as Vaultwarden) survives a lost device. |
