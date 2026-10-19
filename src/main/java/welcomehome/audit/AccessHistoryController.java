package welcomehome.audit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import welcomehome.audit.AccessHistory.Cursor;
import welcomehome.audit.AccessHistory.HistoryEvent;
import welcomehome.auth.CurrentOwner;
import welcomehome.locks.Locks;

/**
 * A lock's history, or one of its links' with {@code ?link=}, in the lock's time zone.
 */
@Controller
class AccessHistoryController {

    static final int PAGE_SIZE = 50;

    private final Locks locks;
    private final AccessHistory history;
    private final MessageSource messages;

    AccessHistoryController(Locks locks, AccessHistory history, MessageSource messages) {
        this.locks = locks;
        this.history = history;
        this.messages = messages;
    }

    @GetMapping("/locks/{lockId}/history")
    String history(CurrentOwner owner, @PathVariable UUID lockId, @RequestParam(required = false) @Nullable UUID link,
                   @RequestParam(required = false) @Nullable Instant before,
                   @RequestParam(required = false) @Nullable UUID beforeId, Locale locale, Model model) {
        var lock = locks.find(owner.id(), lockId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (link != null) {
            model.addAttribute("linkLabel", history.linkLabel(owner.id(), lockId, link)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        }
        var zone = ZoneId.of(lock.timeZone());
        var page = history.find(owner.id(), lockId, link, before == null ? null : new Cursor(before, beforeId),
                PAGE_SIZE);
        model.addAttribute("lock", lock);
        model.addAttribute("events", page.events().stream()
                .map(event -> HistoryRow.of(event, zone, outcome(event.reason(), locale)))
                .toList());
        model.addAttribute("filtered", link != null);
        model.addAttribute("paged", before != null);
        model.addAttribute("newerUrl", url(lockId, link, null));
        if (page.olderBefore() != null) {
            model.addAttribute("olderUrl", url(lockId, link, page.olderBefore()));
        }
        return "audit/history";
    }

    /** What an event's reason code means to the owner; a code no outcome has is shown as it is. */
    private String outcome(@Nullable String reason, Locale locale) {
        return AccessOutcome.ofCode(reason)
                .map(outcome -> messages.getMessage("outcome." + outcome.code(), null, locale))
                .orElse(reason == null ? "" : reason);
    }

    private static String url(UUID lockId, @Nullable UUID link, @Nullable Cursor before) {
        return UriComponentsBuilder.fromPath("/locks/{lockId}/history")
                .queryParamIfPresent("link", Optional.ofNullable(link))
                .queryParamIfPresent("before", Optional.ofNullable(before).map(Cursor::occurredAt))
                .queryParamIfPresent("beforeId", Optional.ofNullable(before).map(Cursor::id))
                .buildAndExpand(lockId)
                .encode()
                .toUriString();
    }

    /**
     * An event as the page shows it.
     *
     * @param outcome what happened, in words
     */
    record HistoryRow(ZonedDateTime occurredAt, UUID linkId, String linkLabel, AccessResult result, String outcome,
                      @Nullable String ip) {

        static HistoryRow of(HistoryEvent event, ZoneId zone, String outcome) {
            return new HistoryRow(event.occurredAt().atZoneSameInstant(zone), event.linkId(), event.linkLabel(),
                    event.result(), outcome, event.ip());
        }
    }
}
