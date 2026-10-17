package welcomehome.audit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import welcomehome.audit.AccessHistory.HistoryEvent;
import welcomehome.auth.CurrentOwner;
import welcomehome.locks.Locks;

/**
 * A lock's history, or one of its links' with {@code ?link=}, in the lock's time zone.
 */
@Controller
class AccessHistoryController {

    static final int PAGE_SIZE = 50;

    /** What each reason code that {@link AccessEvent} records means to the owner. */
    private static final Map<String, String> OUTCOMES = Map.ofEntries(
            Map.entry("opened", "Abierta"),
            Map.entry("not-confirmed", "Abierta sin confirmar"),
            Map.entry("too-many-attempts", "Denegado: demasiados intentos"),
            Map.entry("revoked", "Denegado: enlace revocado"),
            Map.entry("not-yet-valid", "Denegado: todavía no válido"),
            Map.entry("expired", "Denegado: caducado"),
            Map.entry("unavailable", "Denegado: puerta no disponible"),
            Map.entry("nuki-error", "Error: Nuki no responde"),
            Map.entry("lock-failed", "Error: la cerradura no pudo abrir"),
            Map.entry("night-mode", "Error: modo nocturno"));

    private final Locks locks;
    private final AccessHistory history;

    AccessHistoryController(Locks locks, AccessHistory history) {
        this.locks = locks;
        this.history = history;
    }

    @GetMapping("/locks/{lockId}/history")
    String history(CurrentOwner owner, @PathVariable UUID lockId, @RequestParam(required = false) @Nullable UUID link,
                   @RequestParam(required = false) @Nullable Instant before, Model model) {
        var lock = locks.find(owner.id(), lockId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (link != null) {
            model.addAttribute("linkLabel", history.linkLabel(owner.id(), lockId, link)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        }
        var zone = ZoneId.of(lock.timeZone());
        var page = history.find(owner.id(), lockId, link, before, PAGE_SIZE);
        model.addAttribute("lock", lock);
        model.addAttribute("events", page.events().stream().map(event -> HistoryRow.of(event, zone)).toList());
        model.addAttribute("filtered", link != null);
        model.addAttribute("paged", before != null);
        model.addAttribute("newerUrl", url(lockId, link, null));
        if (page.olderBefore() != null) {
            model.addAttribute("olderUrl", url(lockId, link, page.olderBefore()));
        }
        return "audit/history";
    }

    private static String url(UUID lockId, @Nullable UUID link, @Nullable Instant before) {
        return UriComponentsBuilder.fromPath("/locks/{lockId}/history")
                .queryParamIfPresent("link", Optional.ofNullable(link))
                .queryParamIfPresent("before", Optional.ofNullable(before))
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

        static HistoryRow of(HistoryEvent event, ZoneId zone) {
            var reason = event.reason() == null ? "" : event.reason();
            return new HistoryRow(event.occurredAt().atZoneSameInstant(zone), event.linkId(), event.linkLabel(),
                    event.result(), OUTCOMES.getOrDefault(reason, reason), event.ip());
        }
    }
}
