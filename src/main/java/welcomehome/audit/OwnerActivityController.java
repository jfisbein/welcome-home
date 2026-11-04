package welcomehome.audit;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import welcomehome.auth.CurrentOwner;
import welcomehome.config.TimeZoneNames;
import welcomehome.db.OwnerTransactions;

/**
 * The owner's account activity: their latest sign-ins and changes to links, with where each came from, so that
 * they can tell whether someone else has been in their account.
 */
@Controller
class OwnerActivityController {

    /** The page shows this many entries, the latest. */
    static final int LIMIT = 100;

    private final OwnerActivityDao activity;
    private final OwnerTransactions ownerTransactions;
    private final AttemptRows attemptRows;
    private final MessageSource messages;
    private final TimeZoneNames timeZoneNames;
    private final AccessEvents accessEvents;

    OwnerActivityController(OwnerActivityDao activity, OwnerTransactions ownerTransactions, AttemptRows attemptRows,
                            MessageSource messages, TimeZoneNames timeZoneNames, AccessEvents accessEvents) {
        this.activity = activity;
        this.ownerTransactions = ownerTransactions;
        this.attemptRows = attemptRows;
        this.messages = messages;
        this.timeZoneNames = timeZoneNames;
        this.accessEvents = accessEvents;
    }

    @GetMapping("/settings/activity")
    String show(CurrentOwner owner, Locale locale, Model model) {
        // Entries span locks in several zones, and sign-ins have none: the server's, as for passkeys.
        var zone = ZoneId.systemDefault();
        var rows = ownerTransactions.execute(owner.id(), () -> activity.findLatest(owner.id(), LIMIT)).stream()
                .map(entry -> row(entry, zone, locale))
                .toList();
        model.addAttribute("rows", rows);
        model.addAttribute("limited", rows.size() == LIMIT);
        model.addAttribute("limit", String.valueOf(LIMIT));
        model.addAttribute("timeZone", timeZoneNames.of(zone.getId(), locale));
        model.addAttribute("retentionDays", String.valueOf(accessEvents.retention().toDays()));
        model.addAttribute("ipRetentionDays", String.valueOf(accessEvents.ipRetention().toDays()));
        return "audit/activity";
    }

    private ActivityRow row(StoredActivity entry, ZoneId zone, Locale locale) {
        var what = messages.getMessage("activity.kind." + entry.kind(), null, locale);
        return new ActivityRow(entry.occurredAt().atZoneSameInstant(zone), what, detail(entry, locale),
                attemptRows.origin(entry.ip(), entry.country(), locale));
    }

    /** With which passkey an owner signed in, or which link of which lock they changed. */
    private String detail(StoredActivity entry, Locale locale) {
        if (entry.linkId() == null) {
            var passkey = entry.passkey();
            return passkey == null ? "" : messages.getMessage("activity.passkey", new Object[] {passkey}, locale);
        }
        var lockName = Objects.requireNonNullElse(entry.lockName(), "");
        var label = entry.linkLabel();
        return label == null
                ? messages.getMessage("activity.link.gone", new Object[] {lockName}, locale)
                : messages.getMessage("activity.link", new Object[] {label, lockName}, locale);
    }
}
