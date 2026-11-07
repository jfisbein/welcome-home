# Owners and signing in

Read when: changing `auth` or `admin`: passkeys, invitations, recovery links, sessions, an owner's email or account, what admins may do.

See also: [privacy](privacy.md) (sign-ins in the owner's activity), [notifications](notifications.md) (emails about the account).

## Decisions

| Decision | Why |
|----------|-----|
| Sessions end when what let them in is gone: deleting a passkey ends the owner's other sessions, deleting an owner all of theirs | A lost phone must not stay signed in, and a deleted admin must not keep their role, until the session expires. `OwnerSessions` keeps them in memory, as Tomcat does, so a restart loses nothing it needs. Spring's `SessionRegistry` was not used: it hears of sessions through a session strategy, which `WebAuthnConfigurer` does not set. Nor does it set the strategy that changes the session id on signing in, so `SecurityConfiguration` does. |
| Passkeys only, invitations only, and passkeys must verify their user | No passwords to leak; a private app with few, known users. A passkey is the only factor, so registering and signing in require user verification (a PIN, fingerprint or face): a security key without a PIN, which anyone holding it could touch, is refused. Phones, computers and password managers verify anyway. |
| Admins see only emails and dates | An admin is still just an owner to RLS. Recovery links are the exception the user chose: whoever holds one signs in as that owner, so the admin who creates one could. The owner is emailed when one is created and when a passkey is added, so its use is not hidden from them. |
| An owner changes their email at once, and the new address is verified like a new owner's; the previous one, if verified, is told; their other sessions end | The email is not a credential, as signing in takes a passkey: it is where notices go and the name passkeys know the owner by, so a typo at registration must be fixable without the old address. Notices stop until the new one is verified, as for a new owner. Telling the previous address keeps a change made from someone else's hands from going unnoticed. The other sessions still know the owner by the previous email, so they end; the one that asked goes on under the new one. Passkeys on the owner's devices keep showing the previous email: nothing tells them, and they still sign in. |
| An owner closes their own account, unless they are the only admin | The admin panel did it for them before, so a mistyped email meant asking an admin. The only admin cannot, as an admin must always be left, as in the admin panel. |
| Recovery adds a passkey and keeps the old ones | The user's choice; the owner then deletes the lost device's passkey at `/settings/passkeys`, which never deletes the last one. |
| A form from a page whose session ended goes to `/login?expired`, and "Salir" to `/login?logout`, instead of a 403 | Sessions live in memory, so every update in Dockge ends them, and so do 30 minutes without use; the form's CSRF token went with the session, and the owner got a 403 for pressing "Salir" (the user's report, 2026-11-04). Only for an HTML form from a browser that sent a session the server no longer has: a bad token otherwise may be a forged request, and scripts read the 403 themselves. Spring's `invalidSessionUrl` was not used: it would send any request with an ended session's cookie to sign in, an owner opening one of their own guest links among them. |

## Tests

- **Passkeys**: `PasskeyEmulator` (webauthn4j-test) answers registration and sign-in like a browser with an authenticator, so the real WebAuthn endpoints are exercised end to end.
- **Sessions**: `PasskeyManagementTests` (deleting a passkey ends the other sessions, signing in changes the session id), `AdminPanelTests` (a deleted owner's session ends), `EndedSessionTests` (a form from a page whose session ended goes to sign in, against a running server).
