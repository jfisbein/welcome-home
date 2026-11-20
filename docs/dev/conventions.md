# Conventions

Read when: writing any code: the rules that hold everywhere.

See also: [web](web.md) for templates and scripts, [database](database.md) for data access and Liquibase, [AGENTS.md](../../AGENTS.md#rules) for what stays out of the repository and commits.

- **Language**: code, comments, javadoc, docs and commit messages in English. Table, column and enum names in English. UI texts only in `i18n/messages*.properties` (Spanish the default bundle; English, French and Italian the others): never in Java, templates or JavaScript, which `TemplateTextTests` checks. A message with markup goes through `th:utext`, with every value escaped by `#strings.escapeXml`; an apostrophe in a message is written `''`. A new language: "Adding a language" in `docs/features.md`.
- **Javadoc** on every public class and method (`-Xdoclint:all -Werror` fails `check`). Comment the why, sparingly.
- **Nullness**: everything is non-null unless `@Nullable` (JSpecify); every package has `@NullMarked`; NullAway fails the build. Spring 7's `JdbcClient.query(X.class).list()` returns `List<@Nullable X>`: use `new SimplePropertyRowMapper<>(X.class)` or a lambda for lists.
- **Compatibility**: within a major version every release is backward compatible, as production pulls `:vN` without reading release notes and rolls back by pinning the previous release. A change that breaks any of that starts a new major version: 3.0.0 did, taking Nuki's webhooks, the pasted API token and `nuki_connection.credential` out (changeset 039), and 4.0.0 after it, dropping columns nothing read and naming owners by their id in the passkey store (changesets 040 and 041); `MigrationCompatibilityTests.COMPATIBLE_FROM` moves past what breaks each time, and is 042.
- **Configuration**: `application.yaml` (and `application-dev.yaml`); `@ConfigurationProperties` records; durations in ISO-8601 (`PT1M`).
