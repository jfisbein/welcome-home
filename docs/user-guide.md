# User guide

For owners: people who use welcome-home to let guests through their door. It needs no technical knowledge. Labels are quoted as the Spanish pages show them; your pages may be in another language, which you can change in **Configuración → Preferencias**.

## In short

1. You get an invitation, and create a passkey.
2. You connect your Nuki account, and your locks appear.
3. You create a link for a guest and send it to them.
4. The guest opens the link on their phone, and slides to open.

## Getting in

**Your invitation.** Someone who runs welcome-home sends you a link. It works once, for 72 hours. Open it on the device you want to sign in with, enter your email and a name for the passkey, such as "iPhone", and press **Crear passkey**.

A passkey replaces a password: your phone or computer unlocks it with your fingerprint, face or PIN. A password manager such as iCloud Keychain or Google Password Manager can hold it too, and then it works on all your devices.

**Confirm your email.** You get an email with a link. Open it and press the button: until then, welcome-home sends you no other email.

**Signing in later.** Press **Entrar con passkey**.

**A new phone or computer.** If your password manager does not bring your passkey to it, sign in on it with your phone, by the QR code your browser offers, and add one in **Configuración → Passkeys**.

**If you lost your passkey**, and have no other, ask whoever runs welcome-home for a new passkey link. It also works once, for 72 hours, and asks for a name for the new passkey. You keep your other passkeys. Then delete the lost one in **Configuración → Passkeys**, where the names tell them apart, which also signs out your other devices.

## Connecting Nuki

In **Configuración → Nuki**, press **Conectar con Nuki**, sign in to Nuki and accept. Your locks then appear in **Cerraduras**.

- **Nuki asks to let welcome-home manage your devices' authorizations?** It never creates or changes any, but Nuki requires that permission to tell welcome-home at once whether the door opened.
- **No locks appear?** welcome-home sees only the devices activated for Nuki Web, one by one, in the Nuki app. The page has a guide: **¿Acabas de instalar tu Nuki?**
- **A lock you added later?** It appears by the next day, or at once if you press **Actualizar desde Nuki** in **Cerraduras**.
- **"Conectar con Nuki" is not offered?** That installation connects with an API token instead, and the page explains how to get one.

## Creating a link

On the home page, press **Crear enlace** next to the lock. Fill in:

- **Para quién es:** a reminder for you, such as "Ana, fin de semana". Guests do not see it.
- **Desde** and **Hasta:** when it works, at the lock's time.
- **PIN (opcional)**, if you want: 4 to 8 digits. The guest must type it right after sliding, so that someone who only has the link, because it was forwarded or leaked, cannot open the door. Tell the guest the PIN yourself, apart from the link. The eye beside the field shows or hides it, and you can read it again whenever you edit the link; take it off by emptying the field. The list says "Pide un PIN" under the links that ask for one. After a few wrong PINs (5 by default) the link refuses every attempt, the right PIN too, for about 15 minutes, and you get the usual email about a refused attempt, at most one an hour per link.
- **Limitar a ciertos días y horas**, if you want: for instance, a cleaner on Mondays from 9:00 to 13:00. A closing time before the opening time means the next day, and **Todo el día** takes the whole day.

Press **Crear enlace**, and then **Copiar** or **Compartir** to send it. WhatsApp or Telegram showing a preview of it opens nothing: only sliding does.

A link can be copied again from the list for as long as it is pending or active.

## Changing a link

In the lock's **Enlaces**:

| You want to | Press | What happens |
|---|---|---|
| Change the dates, hours or who it is for | **Editar** | The link stays the same, so there is nothing to resend |
| Stop it for good | **Anular** | It stops opening at once, and this cannot be undone |
| Replace a link that reached the wrong person | **Cambiar enlace** | The old link stops working at once, and you send the new one. The dates stay the same |

A link that expired or was cancelled moves under **Caducados y anulados**, folded below the others. It is deleted a year after it ended, with its history; **Borrar** deletes it now.

## What your guest sees

The guest needs no app or account. They open the link and slide **Desliza para abrir** to the end, standing at the door, then push it when they hear the lock. If your lock has a building entrance set up, they see two sliders: the entrance first, then the door. Above them they read the lock's instructions, if you wrote any, from the moment you send the link until it ends.

A touch by mistake does nothing: the slider must go all the way across. If the link has a PIN, the guest is asked for it once they have slid, and can type it again without sliding again if it was wrong.

So that nobody opens your door by mistake from elsewhere, say sliding at home to try the link, a lock can check that its guest is in front of it: in **Estado y ajustes**, under **Comprobar que el invitado está delante de la puerta**, once the lock has its location. With **Si parece lejos, pedirle que lo confirme**, a guest who seems far reads that the door will really open, waits 10 seconds and slides again to open; with **Si está claramente lejos, no abrir**, they cannot open from far away at all. A guest whose phone does not say where they are can always open by confirming. **Distancia a la que cuenta como delante** is how far still counts: indoors a phone can be tens of metres off, so try it at your door first, with a link of your own, and widen it if needed. The history shows how far each guest was, and if a guest opens after confirming while they seemed far, or without their phone saying where they were, you get an email at once, even with opening emails off, as your door may have been left open.

## Checking your locks

The home page and **Cerraduras** warn you in red about a lock that Nuki reports as having no connection, a critical battery, a blocked motor or Auto Lock off. Nuki sometimes reports no connection for a lock that works: the warning then goes away once a guest opens the door, and comes back if an opening fails. For more, press **Estado y ajustes** on the lock, where you can also change its name and its time zone, and write **Instrucciones para tus invitados**: the address, the floor or where the key is, which every guest of that lock reads on their link's page. **Ubicación de la cerradura** is where the door is, which only you see. If you set it in the Nuki app, the page offers it: check it with **Ver en el mapa** and save. Otherwise paste it from Google Maps, or press **Usar mi ubicación actual** standing at the door.

**Auto Lock off** means that the door stays unlocked after a guest opens it. Turn it on in the Nuki app. If you relock some other way, tick **El bloqueo lo gestiono de otra forma**, and the warning goes.

**Who has come in.** The home page shows the latest attempts, and each lock's **Historial** all of them: when, with which link, what happened and from where. Attempts are kept for a year, and their IP address for 90 days: after that only the country remains. Your guests' page tells them so.

**Emails.** In **Configuración → Avisos**, choose whether you hear of openings, failures, refused attempts, problems with Nuki and a lock's problems: a critical battery, no connection or a blocked motor, once a day at most and only when they start. Use **Enviarme un email de prueba** to check that they arrive. Emails about your account always come: a passkey added, and a link created, edited or given a new address, so that you hear of one you did not make. They never carry the link's address.

## If a guest cannot get in

| The guest sees | What it means | What to do |
|---|---|---|
| "Este enlace todavía no es válido" | Its start has not come | Wait, or change **Desde** with **Editar** |
| "A esta hora este enlace no abre la puerta" | Outside its hours | Wait, or change the hours with **Editar** |
| "Este enlace ha caducado" | Its end has passed | **Editar** can extend it, and the link stays the same |
| "Este enlace se ha anulado" | You cancelled it | Create a new link |
| "Enlace no válido" | Mistyped, replaced by **Cambiar enlace**, or deleted a year after it ended | Send the current link, or create one |
| "Esta puerta no está disponible" | The lock left your Nuki account, or Nuki is disconnected | Look at your home page, which says which: **Volver a conectar Nuki** if it offers that |
| "La cerradura no responde" | Nuki or the lock failed | Try again in a few seconds. Check the lock's connection and battery |
| "Hemos pedido a la cerradura que abra, pero no lo ha confirmado" | The lock was asked to open and did not say that it did | Try pushing the door before sliding again |
| "El PIN no es correcto" | They typed another PIN | Check it with **Editar**, where you can read it, and tell them |
| "La cerradura está en modo nocturno" | The lock refuses in night mode | Change it in the Nuki app |

If the door really must open now, you can always use the Nuki app yourself.

## Your account

**Configuración → Cuenta** shows the email we write to. If you change it, we send a link to the new one to confirm it, and tell the old one: until you open the link, you get no emails. Your passkeys still work, though your phone may keep showing the old email next to them.

**Configuración → Actividad** lists your latest sign-ins, with the passkey each used, and every change to your links, with where it came from: a flag, and the IP address for 90 days. If something there was not you, delete the passkey you do not recognise in **Configuración → Passkeys**, which signs out its sessions, and disable the links you did not create.

## Leaving

Press **Darme de baja** in **Configuración → Cuenta** to delete your account, with your locks, links and history: your links stop opening at once. Whoever runs welcome-home can also do it for you, and the only admin cannot leave until there is another. Copies in backups go within 30 days.

To disconnect Nuki yourself, press **Desconectar Nuki** in **Configuración → Nuki**. Your locks and links stay, but do not open until you connect again. If you pasted an API token, also delete it in Nuki Web.
