# Build, release and deployment

Read when: releasing a version, changing the image, the workflow, `deploy/` or backups. The build commands are in [AGENTS.md](../../AGENTS.md#build-and-run).

See also: [deployment](../deployment.md), [runbook](../runbook.md), [conventions](conventions.md) (compatibility).

## Image and releases

`.github/workflows/image.yml`: pull requests build, test and build the image without pushing; pushes to `main` publish `edge`; tags `vX.Y.Z` publish `vX.Y.Z`, `vX` and `latest` (prereleases only their own tag), and then create the GitHub release.

Release: a pull request named `Version X.Y.Z` from branch `release-X.Y.Z` that bumps `version` in `build.gradle.kts`; once merged, tag the merge commit `vX.Y.Z` and push the tag. Its description becomes the GitHub release's notes, so it is written for whoever deploys: what changed since the last release, and anything to do before deploying. The workflow drops its first paragraph when it starts with "Sets the project's version", and Claude Code's attribution (`.github/scripts/release_notes.py`). Production follows `:v2`, so `deploy/compose.yaml` changes only for a new major version. Upgrading production and everything else on the server is in the runbook.

## Decisions

| Decision | Why |
|----------|-----|
| `CF-Connecting-IP` for the guest's IP, `forward-headers-strategy=framework` | The tunnel is the only way in, so Cloudflare's headers are trustworthy; `X-Forwarded-For` starts with whatever the client sent. |
| No ports published; own cloudflared in the stack | With a published port the app was reachable from the LAN and the forwarded headers could be spoofed. |
| Off-site dumps by Databasus over `dbbackup`, an internal network that only `db` and Databasus join, as `backup_reader` (`BYPASSRLS`, `pg_read_all_data`) | No published port, so the database stays out of the LAN and keeps no way out, and the tool reaches neither `app` nor the internet through it. A role that writes nothing rather than the superuser, which could also run programs (`COPY ... TO PROGRAM`). Created by `db-init`, not Liquibase: making a `BYPASSRLS` role takes a superuser, and dumps carry its `CONNECT` grant, so it must exist before `restore.sh` runs on a new server, when a restored changelog would say that the changeset already ran. |
| Database passwords as files (`configtree:`), keysets as Docker secrets | No secrets in environment variables or in the image. |
| Keysets backed up apart from the dumps (Vaultwarden) | A stolen dump alone does not expose the Nuki tokens. |
| Releases are `vX.Y.Z` tags; production follows the major version's tag (`:v2`) | Upgrading is Dockge's Update, with no backup and no change on the server. The running release is the image's `org.opencontainers.image.version` label; rolling back is pinning the previous one. It holds because releases within a major are backward compatible ([conventions](conventions.md)). |

## Tests

- **Backups**: `.github/scripts/test_backup_restore.sh`, in CI, runs production's stack, `backup.sh` and `restore.sh` on throwaway secrets and test data.

## Off-site dumps

**Off-site dumps by Databasus** (runbook, First start, step 8). On a volume older than `backup_reader`, the role is created by hand. Tried first on a copy of `db` (`COMPOSE_PROJECT_NAME=whtest`) holding a production dump: `db-init` creates the role, `backup_reader` dumps every row and may not write, `docker compose down` leaves `dbbackup` while another container is attached ("Resource is still in use", exit 0), and a dump with its grant restored by `restore.sh` on a Postgres without the role stops at the grants with `role "backup_reader" does not exist`, after the data, without the tables' grants to `app_user`.
