# Security

welcome-home opens real doors, so a weakness in it can let someone into a home. Thank you for taking the time to report one.

## Reporting a vulnerability

Report it privately, through GitHub: the repository's **Security** tab, **Report a vulnerability**. Only the maintainer sees the report, and you can follow and discuss it there until it is fixed.

Please do not open a public issue, pull request or discussion about it, and do not test it against anyone's installation but your own.

Helpful to include: what an attacker could do, the steps to reproduce it on a local copy (`README.md`, Development), and the release or commit you looked at.

## What is covered

- The application in this repository, at the latest `v2` release and on `main`.
- The production stack in `deploy/` as documented: `compose.yaml`, the database roles, backups and the Cloudflare Tunnel's place in it.

Not covered: Nuki's own app, API and locks, Cloudflare, and installations that do not follow `docs/deployment.md`. Weaknesses in them are better reported to their makers.

## What to expect

This is a personal project, maintained in spare time. A report is answered as soon as possible, usually within a week. A confirmed vulnerability is fixed in a new release, and once it is out its advisory is published, crediting you if you wish. There is no bug bounty.
