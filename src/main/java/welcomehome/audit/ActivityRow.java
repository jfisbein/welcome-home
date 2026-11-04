package welcomehome.audit;

import java.time.ZonedDateTime;

import welcomehome.audit.AttemptRow.Origin;

/**
 * An entry of an owner's activity, as its page shows it.
 *
 * @param occurredAt when, in the server's time zone
 * @param what       what was done, in the reader's language
 * @param detail     with which passkey, or to which link of which lock
 * @param origin     where from
 */
record ActivityRow(ZonedDateTime occurredAt, String what, String detail, Origin origin) {
}
