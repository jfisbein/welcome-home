# Deployment

How the app is packaged and what its production stack is made of. To run it day to day, see the [runbook](runbook.md).

The app needs `BASE_URL`, its public URL, such as `https://welcome.example`, to build invitation and access links: an http or https URL with a host and nothing after it, or the app does not start. Its host is also the passkeys' relying party: passkeys created for one domain do not work on another.

## Docker image

```sh
./gradlew bootJar        # or ./gradlew build, as CI does
docker build -t welcome-home .
```

The `Dockerfile` does not compile: it takes the jar Gradle left in `build/libs/welcome-home.jar` and runs it on a JRE 25 (`eclipse-temurin`, Ubuntu Noble) as the unprivileged user `app` (uid 10001), with compact object headers (`-XX:+UseCompactObjectHeaders`), from Spring Boot's layers, so a new release only changes the application layer. `.dockerignore` lets in only that jar, so keysets never end up in the image.

The container listens on 8080 and is configured with environment variables:

| Variable                                                   | Value                                              |
|------------------------------------------------------------|----------------------------------------------------|
| `BASE_URL`                                                 | the public URL, such as `https://welcome-home.example.com` |
| `TZ`                                                       | the server's time zone, for the daily jobs, such as `Europe/Madrid` |
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD`          | Postgres as `app_user`                             |
| `SPRING_LIQUIBASE_USER`, `SPRING_LIQUIBASE_PASSWORD`       | Postgres as `migrator`                             |
| `AEAD_KEYSET_FILE`, `MAC_KEYSET_FILE`                      | paths of the mounted keysets                       |

The keysets are mounted read-only and must be readable by uid 10001. The image has no curl or wget; a health check can use bash: `exec 3<>/dev/tcp/localhost/8080; printf 'GET /actuator/health HTTP/1.0\r\n\r\n' >&3; grep -q UP <&3`.

## Production

`.github/workflows/image.yml` runs `./gradlew build` on every pull request, push to `main` and version tag, and builds the image. Only after a push is the image published to GHCR as `ghcr.io/jfisbein/welcome-home`:

| Event                   | Image tags                                              |
|-------------------------|---------------------------------------------------------|
| pull request            | none: the image is built, which checks the `Dockerfile` |
| push to `main`          | `edge`                                                  |
| tag `v1.2.3`            | `v1.2.3`, `v1` and `latest`                             |
| tag `v1.3.0-rc.1`       | `v1.3.0-rc.1` only: a prerelease moves neither `v1` nor `latest` |

A release is a tag on `main`: `git tag v1.2.3 && git push origin v1.2.3`. Once its image is published, the workflow also creates the GitHub release, with the description of the release pull request (branch `release-1.2.3`) as its notes. `deploy/compose.yaml` runs `:v2`, the latest 2.x release: upgrading is pulling it again, and rolling back is pinning the previous release, such as `:v2.0.0`. That works because releases within a major version are backward compatible, migrations included (AGENTS.md, Conventions).

[The runbook](runbook.md) covers running it: first start, releases, rollback, restoring a dump, rotating the AEAD keyset and what to check when something fails.

`deploy/` is the Dockge stack: copy it to the stack's folder on the server. `compose.yaml` runs four services and publishes no port:

- `db`: Postgres 18. `db-init/01-create-roles.sh` creates `migrator`, `app_user` and `backup_reader` on the first start. Its networks are internal: it has no way out. `dbbackup`, the second one, is for a backup tool on the same Docker host (see [Backups](#backups)).
- `app`: the image, with a read-only file system, no capabilities, at most 1 GiB of memory (the JVM takes 75% of it for its heap), `TZ=Europe/Madrid` and `BASE_URL=https://welcome-home.example.com`. It reads its database and SMTP passwords and Nuki's OAuth2 client as properties from `/run/secrets/config/` (`spring.config.import=configtree:`), not from environment variables.
- `backup`: dumps the database to `backups/` (see [Backups](#backups)).
- `cloudflared`: connects the tunnel, whose public hostname `welcome-home.example.com` points to `http://app:8080`. The tunnel is the only way in, which is what makes `CF-Connecting-IP` and `X-Forwarded-Proto` trustworthy.

Before the first `docker compose up`, create `secrets/` next to `compose.yaml`:

| File                 | Content                                                  |
|----------------------|----------------------------------------------------------|
| `postgres_password`  | Postgres superuser's password, such as `openssl rand -base64 24` |
| `migrator_password`  | `migrator`'s password                                    |
| `app_user_password`  | `app_user`'s password                                    |
| `backup_reader_password` | `backup_reader`'s password, for the off-site backup tool |
| `aead-keyset.json`   | the AEAD keyset (`./gradlew generateKeysets`)            |
| `mac-keyset.json`    | the MAC keyset                                           |
| `tunnel_token`       | the Cloudflare tunnel's token                            |
| `smtp_username`      | the SMTP user for owners' emails; empty for none         |
| `smtp_password`      | its password                                             |
| `nuki-oauth-client-id` | welcome-home's OAuth2 client id, from Nuki Web's API page; empty without an OAuth2 client |
| `nuki-oauth-client-secret` | its secret                                           |

Write the passwords without a trailing newline (`printf`, or `tr -d '\n'`). Compose mounts each file with its host permissions and the containers run as different users (Postgres 999, the app 10001, cloudflared 65532), so: `chmod 700 secrets` (only root on the host can read them) and `chmod 444 secrets/*` (each container sees only its own). The database passwords are only read on the first start, when the volume is empty.

Create `backups/` as well, owned by the `backup` service's user: `install -d -o 999 -g 999 -m 700 backups`.

### Backups

The `backup` service dumps the database with `pg_dump` (custom format) to `backups/welcome_home-<date>-<time>.dump` when it starts and every day at 04:00, after the access events' purge, and deletes the dumps older than 30 days (`BACKUP_TIME`, `BACKUP_RETENTION_DAYS`). It dumps as `backup_reader`, which reads every row, as it has `BYPASSRLS`, and can write nothing. A stack set up earlier dumps as the superuser, which still works; to change it, edit the `backup` service as `deploy/compose.yaml` has it now. Its health check fails when the last dump is more than 25 hours old. To take a dump now, such as before an upgrade: `docker compose exec backup /backup/backup.sh now`.

- **Off the server**: a database backup tool on the same Docker host, such as Databasus, dumps the database every day to another provider, such as Backblaze B2. Its stack joins `dbbackup` as the external network `welcome-home_dbbackup` and connects to `welcome-home-db-1:5432` as `backup_reader`: no port is published, and the tool reaches neither `app` nor the internet through it. Alternatively, a restic job (such as zerobyte) copies `backups/` after 04:00. Either way, keep its copies for 30 days at most, deleted ones included (a bucket that keeps old versions keeps them too), so that 30 days after an owner is deleted no copy of their data is left.
- **Keysets**: never with the dumps. Keep `aead-keyset.json` and `mac-keyset.json` in a password manager, such as Vaultwarden, and update them there whenever they change. A dump without them still restores, but every owner must connect Nuki again and every access link be regenerated. The other secrets can be generated anew on a new server.

The dumps hold the encrypted Nuki tokens, owners' e-mail addresses, link labels and guests' IP addresses: only root on the server can read them.

To restore one, with the stack's `compose.yaml` next to the script:

```sh
./restore.sh backups/welcome_home-20261018-040000.dump
```

It stops `app` and `backup`, drops the database and recreates it from the dump with its owner, grants and Liquibase history, and starts both again: the app migrates it if the dump comes from an older version, and `backup` takes a dump of the restored database. On a new server, create `secrets/` (with the keysets from the password manager) and `backups/` first and start `db`, so that `db-init` creates the roles; the passwords may be new ones. `backup_reader` must be among them: dumps carry its `CONNECT` grant, and `pg_restore --create` stops without it.
