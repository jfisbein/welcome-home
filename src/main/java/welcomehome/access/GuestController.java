package welcomehome.access;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import welcomehome.access.GuestLinks.VerifiedLink;
import welcomehome.audit.AccessEvents;
import welcomehome.audit.AccessOutcome;
import welcomehome.config.Languages;
import welcomehome.config.TimeZoneNames;
import welcomehome.links.LinkSchedules;
import welcomehome.links.LinkStatus;
import welcomehome.locks.LocationCheck;

/**
 * The page a guest's URL opens. It only informs: messaging apps fetch links to preview them, so a GET never
 * opens the door. Opening is a POST once the guest slides to open. Search engines are asked not to
 * index the page. It creates no session: it has no CSRF token to keep, as opening needs none. Each door the link
 * opens has its own slide: the lock, and before it the Opener at its building's entrance, if it has one. Under
 * them, what sliding keeps about the guest and for how long.
 */
@Controller
class GuestController {

    /** A page that waits for its link to open looks again at least this often. */
    private static final Duration MAX_REFRESH = Duration.ofHours(6);

    /** The languages a guest may read the page in, each by its own name. */
    private static final List<Language> LANGUAGES = Languages.SUPPORTED.stream()
            .map(language -> new Language(language.toLanguageTag(), Languages.nameOf(language))).toList();

    /**
     * A language on offer.
     *
     * @param tag  its tag, as {@code ?lang=} takes it
     * @param name its name in itself
     */
    record Language(String tag, String name) {
    }

    /** Every outcome's code, for the page to carry what the guest reads after sliding to open. */
    private static final List<String> OUTCOMES = Arrays.stream(AccessOutcome.values()).map(AccessOutcome::code).toList();

    private final GuestLinks guestLinks;
    private final LinkSchedules schedules;
    private final MessageSource messages;
    private final TimeZoneNames timeZoneNames;
    private final AccessEvents accessEvents;
    private final Clock clock;

    GuestController(GuestLinks guestLinks, LinkSchedules schedules, MessageSource messages,
                    TimeZoneNames timeZoneNames, AccessEvents accessEvents, Clock clock) {
        this.clock = clock;
        this.guestLinks = guestLinks;
        this.schedules = schedules;
        this.messages = messages;
        this.timeZoneNames = timeZoneNames;
        this.accessEvents = accessEvents;
    }

    @GetMapping("/o/{token}")
    String guestPage(@PathVariable String token, @RequestParam(required = false) @Nullable String preview,
                     Locale locale, Model model, HttpServletResponse response) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        // For an owner to see what their guest will: the same page, whose slider says what it would do and sends
        // nothing (open.js). Whoever holds the link may ask for it, as it does less than the link.
        model.addAttribute("preview", preview != null);
        model.addAttribute("noindex", true);
        model.addAttribute("sessionless", true);
        model.addAttribute("languages", LANGUAGES);
        var now = clock.instant();
        var found = guestLinks.find(token, now);
        if (found.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return "access/invalid";
        }
        var link = found.get();
        var zone = link.zone();
        model.addAttribute("lockName", link.lockName());
        model.addAttribute("timeZone", timeZoneNames.of(link.timeZone(), locale));
        model.addAttribute("validFrom", link.validFrom().atZone(zone));
        model.addAttribute("validUntil", link.validUntil().atZone(zone));
        model.addAttribute("status", link.status());
        var withinSchedule = true;
        if (link.status().isUsable()) {
            var schedule = link.schedule();
            var pending = link.status() == LinkStatus.PENDING;
            withinSchedule = pending || schedule.allows(now, zone);
            model.addAttribute("schedule", schedules.describe(schedule, locale));
            // Only while the link can open: they may say where the door is, or where a key is.
            model.addAttribute("instructions", link.lock() == null ? null : link.lock().guestInstructions());
            model.addAttribute("opensAt", schedule.firstOpening(pending ? link.validFrom() : now, link.validUntil(),
                    zone).orElse(null));
        }
        model.addAttribute("closed", !withinSchedule);
        // A page opened early loads itself again when its link can open, so that a guest who waits with it open
        // finds the slider without knowing to reload; at most some hours ahead, and then again.
        if (model.getAttribute("opensAt") instanceof ZonedDateTime opensAt
                && (link.status() == LinkStatus.PENDING || !withinSchedule)) {
            var wait = Duration.between(now, opensAt.toInstant()).plusSeconds(1);
            model.addAttribute("refreshSeconds", Math.max(1, Math.min(wait.toSeconds(), MAX_REFRESH.toSeconds())));
        }
        var doors = link.status() == LinkStatus.ACTIVE && withinSchedule ? doors(link, locale) : List.<GuestDoor>of();
        model.addAttribute("doors", doors);
        model.addAttribute("canOpen", doors.stream().anyMatch(GuestDoor::available));
        // Whether the page asks for a PIN once the guest has slid: only the link's owner and holders of its URL know.
        model.addAttribute("pinRequired", !doors.isEmpty() && link.pinRequired());
        model.addAttribute("checksLocation", doors.stream().anyMatch(door -> door.available() && door.checksLocation()));
        // What sliding keeps, for the notice under the doors; as text, so that no language groups the digits.
        model.addAttribute("ipRetentionDays", String.valueOf(accessEvents.ipRetention().toDays()));
        model.addAttribute("retentionDays", String.valueOf(accessEvents.retention().toDays()));
        return "access/guest";
    }

    /** The doors a link opens, in the order the guest reaches them: its lock's building entrance first, if any. */
    private List<GuestDoor> doors(VerifiedLink link, Locale locale) {
        var doors = new ArrayList<GuestDoor>();
        var lock = link.lock();
        var entrance = link.entrance();
        if (entrance != null) {
            doors.add(new GuestDoor("open-entrance", Door.ENTRANCE.code(), true, link.entranceAvailable(),
                    outcomeMessages(true, locale),
                    entrance.locationCheck() != LocationCheck.OFF && link.entrancePlace() != null));
        }
        var opener = lock != null && lock.opener();
        doors.add(new GuestDoor("open", Door.LOCK.code(), opener, link.doorAvailable(),
                outcomeMessages(opener, locale), lock != null && lock.checksLocation()));
        return doors;
    }

    /**
     * What the guest reads after each outcome. An Opener has its own message where a lock's would speak of a lock
     * or of a door that stays open, and a lock's otherwise.
     */
    private Map<String, String> outcomeMessages(boolean opener, Locale locale) {
        var outcomeMessages = new LinkedHashMap<String, String>();
        for (var outcome : OUTCOMES) {
            var message = messages.getMessage("guest.result." + outcome, null, locale);
            if (opener) {
                message = Objects.requireNonNullElse(
                        messages.getMessage("guest.entrance.result." + outcome, null, null, locale), message);
            }
            outcomeMessages.put(outcome, message);
        }
        // What the page says by itself: that the phone has no connection, and, of too many attempts with a link that
        // asks for a PIN, whom to ask for it.
        outcomeMessages.put("offline", messages.getMessage("guest.result.offline", null, locale));
        outcomeMessages.put("too-many-attempts-pin",
                messages.getMessage("guest.result.too-many-attempts.pin", null, locale));
        // Asked to confirm without a position, which the guest reads instead of the distance.
        outcomeMessages.put("confirm-location-unknown",
                messages.getMessage("guest.result.confirm-location-unknown", null, locale));
        return outcomeMessages;
    }
}
