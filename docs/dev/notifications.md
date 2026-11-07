# Notifications

Read when: adding or changing an email to owners, or their preferences.

See also: [owners](owners.md), [privacy](privacy.md) (the email about a link that may open the door).

## Decisions

| Decision | Why |
|----------|-----|
| Emails are sent after commit, on another thread, and dropped if they fail | A guest never waits for SMTP, and a mail problem never fails an opening. At most one email about refused attempts per link per hour, and one about each reason for a failure. |

## Tests

- **Emails**: `MailStandInConfiguration` starts GreenMail as the SMTP server; `NotificationTests` checks each kind of email, preferences, the per-link quiet period, the owner's language and that two simultaneous rejections send one email; `AccountSecurityEmailTests` the recovery and passkey emails; `OwnerActivityTests` the email about a link that may open the door, without its URL; `NotificationTests` also the one about a door opened only because a guest far away confirmed, whatever the preferences and instead of the usual one; `EmailVerificationTests` the link that verifies an owner's address, and that nothing else goes to it before.
