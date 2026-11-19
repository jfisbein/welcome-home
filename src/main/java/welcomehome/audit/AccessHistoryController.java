package welcomehome.audit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import welcomehome.audit.AccessHistory.Cursor;
import welcomehome.auth.CurrentOwner;
import welcomehome.locks.Locks;

/**
 * A lock's history, or one of its links' with {@code ?link=}, in the lock's time zone. An Opener's also shows the
 * attempts at the entrance it opens for other locks' links. It can be narrowed to what the attempts came to
 * ({@code ?result=}) and started at a day ({@code ?until=}), so that "who opened on Saturday night?" is not paging.
 */
@Controller
class AccessHistoryController {

    static final int PAGE_SIZE = 50;

    private final Locks locks;
    private final AccessHistory history;
    private final AttemptRows rows;

    AccessHistoryController(Locks locks, AccessHistory history, AttemptRows rows) {
        this.locks = locks;
        this.history = history;
        this.rows = rows;
    }

    @GetMapping("/locks/{lockId}/history")
    String history(CurrentOwner owner, @PathVariable UUID lockId, @RequestParam(required = false) @Nullable UUID link,
                   @RequestParam(required = false) @Nullable AccessResult result,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate until,
                   @RequestParam(required = false) @Nullable Instant before,
                   @RequestParam(required = false) @Nullable UUID beforeId, Locale locale, Model model) {
        var lock = locks.find(owner.id(), lockId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (link != null) {
            model.addAttribute("linkLabel", history.linkLabel(owner.id(), lockId, link)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        }
        var zone = ZoneId.of(lock.timeZone());
        // A page asked for by its cursor is that page; otherwise the day asked for is the first one shown, whole.
        var from = before != null ? new Cursor(before, beforeId)
                : until != null ? new Cursor(until.plusDays(1).atStartOfDay(zone).toInstant(), null) : null;
        var page = history.find(owner.id(), lockId, link, result, from, PAGE_SIZE);
        model.addAttribute("lock", lock);
        model.addAttribute("events", page.events().stream()
                .map(event -> rows.row(event, zone, locale))
                .toList());
        model.addAttribute("filtered", link != null);
        model.addAttribute("link", link);
        model.addAttribute("result", result);
        model.addAttribute("until", until);
        model.addAttribute("narrowed", result != null || until != null);
        model.addAttribute("paged", before != null || until != null);
        model.addAttribute("newerUrl", url(lockId, link, result, null));
        if (page.olderBefore() != null) {
            model.addAttribute("olderUrl", url(lockId, link, result, page.olderBefore()));
        }
        return "audit/history";
    }

    private static String url(UUID lockId, @Nullable UUID link, @Nullable AccessResult result, @Nullable Cursor before) {
        return UriComponentsBuilder.fromPath("/locks/{lockId}/history")
                .queryParamIfPresent("link", Optional.ofNullable(link))
                .queryParamIfPresent("result", Optional.ofNullable(result))
                .queryParamIfPresent("before", Optional.ofNullable(before).map(Cursor::occurredAt))
                .queryParamIfPresent("beforeId", Optional.ofNullable(before).map(Cursor::id))
                .buildAndExpand(lockId)
                .encode()
                .toUriString();
    }
}
