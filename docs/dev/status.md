# Status and next steps

Read when: choosing what to do next, or after trying for real something listed here: write down what it found.

See also: [nuki](nuki.md#what-was-learned-about-nuki) for what is known and still unknown about Nuki.

## Where things stand

**Where things stand.** What each release changed is in its GitHub release. Tests and desktop Chrome have covered the rest; this is what only real use can still tell:

- **A phone.** Nothing has been tried on one. Chromium's touch emulation covers `open.js`'s logic, not Safari on iOS or a real finger: sliding to open with touch and with screen readers (VoiceOver, TalkBack), the thumb sliding back after an opening, a lock's links as cards below 1024 px, the configuration's tabs in two rows. They were checked in Chrome, on rendered copies of the pages, from 320 to 1280 px.
- **A link's schedule, refused for real.** A slide with the page left open past its hours (`outside-schedule`) is covered only by tests, as a failure there would open a real door. The rest was gone through in production with a test link.
- **Languages.** French, German and Italian have not been read by a native speaker. German, with its long words, is the one to check at phone width.
- **Accessibility, by a person.** axe-core (`AccessibilityTests`) covers what the HTML shows, since 2.15.0; three things need real devices and people: the slide to open with VoiceOver and TalkBack (is "opening", "open" or the failure announced, and can it be done with one finger or a tremor; see also A phone), whether screen readers announce the notices (`<article aria-live="assertive">`, set on a page already loaded, which some do not), and French, German and Italian read by a native speaker (see Languages). Write down here what they find.
- **The Nuki Opener.** Tried only against WireMock and in screenshots of the guest page. The first real check is an owner who has one sliding at their building's entrance with a link of their own. How an Opener logs an opening through the Web API is the open question (see [Nuki's decisions](nuki.md#decisions)): if those openings come out "not confirmed", the app's log has the Opener's entries with Nuki's codes.
- **The Nuki app's labels** in the guide on `/settings/nuki`, Spanish "Características y configuración" and "Nuki Web" first: its steps follow Nuki's help center as read on 2026-10-27 (articles 360016485718 and 4402921104145, which `help.nuki.io` serves to scripts only through Zendesk's API, `/api/v2/help_center/<locale>/articles/<id>.json`), not the Nuki app itself.
- **How long a refresh token lasts** (see [Nuki's OAuth2](nuki.md#what-was-learned-about-nuki)): a connection made on 2026-10-26 and still `OK` after 2027-01-24 means that Nuki's 90 days do not count from the authorization.

## Other ideas

**Other ideas**, roughly by value:

- Owners who cannot use a passkey. First learn why: today almost every device can hold one (iCloud Keychain, Google Password Manager, Bitwarden or 1Password), a computer without one can use the phone's by scanning a QR code, and a security key costs little; help on the invitation page, by platform, may be all it takes. If a real alternative is needed, a one-time code by email: the owner asks for it with their email, gets 6 digits valid for 10 minutes, and types them in the same browser, which a mail scanner opening a link could not use up, and which ties it to the browser that asked. Only to verified addresses, single use, kept as a hash like the verification link, rate limited by email and IP, and an email to the owner on each sign-in this way. Spring Security has one-time token login (`oneTimeTokenLogin()`), but its JDBC store keeps tokens in clear. It makes the email account the owner's credential, weaker than a passkey: so offer it per owner, or only until they add a passkey, and record the trade-off in Decisions. Passwords would add a secret to leak and still need resetting by email, and TOTP alone is a weak factor: neither is worth it here.
- The guest location check ([docs/design/guest-location-check.md](../design/guest-location-check.md)): built; what is left is trying it on real phones (its open questions).
