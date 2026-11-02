# User guide

For owners: people who use welcome-home to let guests through their door. It needs no technical knowledge. Labels are quoted as the Spanish pages show them; your pages may be in another language, which you can change in **Configuración → Preferencias**.

## In short

1. You get an invitation, and create a passkey.
2. You connect your Nuki account, and your locks appear.
3. You create a link for a guest and send it to them.
4. The guest opens the link on their phone, and slides to open.

## Getting in

**Your invitation.** Someone who runs welcome-home sends you a link. It works once, for 72 hours. Open it on the device you want to sign in with, enter your email and press **Crear passkey**.

A passkey replaces a password: your phone or computer unlocks it with your fingerprint, face or PIN. A password manager such as iCloud Keychain or Google Password Manager can hold it too, and then it works on all your devices.

**Confirm your email.** You get an email with a link. Open it and press the button: until then, welcome-home sends you no other email.

**Signing in later.** Press **Entrar con passkey**.

**A new phone or computer.** If your password manager does not bring your passkey to it, sign in on it with your phone, by the QR code your browser offers, and add one in **Configuración → Passkeys**.

**If you lost your passkey**, and have no other, ask whoever runs welcome-home for a new passkey link. It also works once, for 72 hours. You keep your other passkeys. Then delete the lost one in **Configuración → Passkeys**, which also signs out your other devices.

## Connecting Nuki

In **Configuración → Nuki**, press **Conectar con Nuki**, sign in to Nuki and accept. Your locks then appear in **Cerraduras**.

- **No locks appear?** welcome-home sees only the devices activated for Nuki Web, one by one, in the Nuki app. The page has a guide: **¿Acabas de instalar tu Nuki?**
- **A lock you added later?** It appears by the next day, or at once if you press **Actualizar desde Nuki** in **Cerraduras**.
- **"Conectar con Nuki" is not offered?** That installation connects with an API token instead, and the page explains how to get one.

## Creating a link

On the home page, press **Crear enlace** next to the lock. Fill in:

- **Para quién es:** a reminder for you, such as "Ana, fin de semana". Guests do not see it.
- **Desde** and **Hasta:** when it works, at the lock's time.
- **Limitar a ciertos días y horas**, if you want: for instance, a cleaner on Mondays from 9:00 to 13:00. A closing time before the opening time means the next day, and **Todo el día** takes the whole day.

Press **Crear enlace**, and then **Copiar** or **Compartir** to send it. WhatsApp or Telegram showing a preview of it opens nothing: only sliding does.

A link can be copied again from the list for as long as it is pending or active.

## Changing a link

In the lock's **Enlaces**:

| You want to | Press | What happens |
|---|---|---|
| Change the dates, hours or name | **Editar** | The address stays the same, so there is nothing to resend |
| Stop it for good | **Anular** | It stops opening at once, and this cannot be undone |
| Replace an address that reached the wrong person | **Cambiar enlace** | The old address stops working at once, and you send the new one. The dates stay the same |

A link is deleted a year after it ended, with its history.

## What your guest sees

The guest needs no app or account. They open the link and slide **Desliza para abrir** to the end, standing at the door, then push it when they hear the lock. If your lock has a building entrance set up, they see two sliders: the entrance first, then the door.

A touch by mistake does nothing: the slider must go all the way across.

## Checking your locks

The home page and **Cerraduras** warn you in red about a lock with no connection, a critical battery, a blocked motor or Auto Lock off. For more, press **Estado y ajustes** on the lock, where you can also change its name, which guests see, and its time zone.

**Auto Lock off** means that the door stays unlocked after a guest opens it. Turn it on in the Nuki app. If you relock some other way, tick **El bloqueo lo gestiono de otra forma**, and the warning goes.

**Who has come in.** The home page shows the latest attempts, and each lock's **Historial** all of them: when, with which link, what happened and from where. Attempts are kept for a year.

**Emails.** In **Configuración → Avisos**, choose whether you hear of openings, failures, refused attempts and problems with Nuki. Use **Enviarme un email de prueba** to check that they arrive. Emails about your account, such as a passkey added, always come.

## If a guest cannot get in

| The guest sees | What it means | What to do |
|---|---|---|
| "Este enlace todavía no es válido" | Its start has not come | Wait, or change **Desde** with **Editar** |
| "A esta hora este enlace no abre la puerta" | Outside its hours | Wait, or change the hours with **Editar** |
| "Este enlace ha caducado" | Its end has passed | **Editar** can extend it, with the same address |
| "Este enlace se ha anulado" | You cancelled it | Create a new link |
| "Enlace no válido" | Mistyped, replaced by **Cambiar enlace**, or deleted a year after it ended | Send the current link, or create one |
| "Esta puerta no está disponible" | The lock left your Nuki account, or Nuki is disconnected | Look at your home page, which says which: **Volver a conectar Nuki** if it offers that |
| "La cerradura no responde" | Nuki or the lock failed | Try again in a few seconds. Check the lock's connection and battery |
| "Hemos pedido a la cerradura que abra, pero no lo ha confirmado" | The lock was asked to open and did not say that it did | Try pushing the door before sliding again |
| "La cerradura está en modo nocturno" | The lock refuses in night mode | Change it in the Nuki app |

If the door really must open now, you can always use the Nuki app yourself.

## Leaving

Whoever runs welcome-home can delete your account, with your locks, links and history. Copies in backups go within 30 days.

To disconnect Nuki yourself, press **Desconectar Nuki** in **Configuración → Nuki**. Your locks and links stay, but do not open until you connect again. If you pasted an API token, also delete it in Nuki Web.
