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

    /** Cloudflare's code for requests from the Tor network. */
    static final String TOR = "T1";

    /** 🇦, the regional indicator symbol for the letter A. */
    private static final int REGIONAL_INDICATOR_A = 0x1F1E6;

    private final Locks locks;
    private final AccessHistory history;
    private final OutcomeTexts outcomes;
    private final MessageSource messages;

    AccessHistoryController(Locks locks, AccessHistory history, OutcomeTexts outcomes, MessageSource messages) {
        this.locks = locks;
        this.history = history;
        this.outcomes = outcomes;
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
                .map(event -> HistoryRow.of(event, zone, outcomes.describe(event.reason(), locale),
                        country(event.country(), locale)))
                .toList());
        model.addAttribute("filtered", link != null);
        model.addAttribute("paged", before != null);
        model.addAttribute("newerUrl", url(lockId, link, null));
        if (page.olderBefore() != null) {
            model.addAttribute("olderUrl", url(lockId, link, page.olderBefore()));
        }
        return "audit/history";
    }

    /** A country as the history shows it: its flag, and its name in the reader's language. Tor has no flag. */
    private @Nullable Country country(@Nullable String code, Locale locale) {
        if (code == null) {
            return null;
        }
        if (code.equals(TOR)) {
            return new Country(null, messages.getMessage("history.tor", null, locale));
        }
        var name = Locale.of("", code).getDisplayCountry(locale);
        return new Country(flag(code), name.isEmpty() ? code : name);
    }

    /**
     * The flag emoji of an ISO 3166 code: its two letters as regional indicator symbols, which systems draw as
     * the country's flag (Windows shows the two letters instead).
     */
    static @Nullable String flag(String code) {
        if (code.length() != 2 || !code.chars().allMatch(letter -> letter >= 'A' && letter <= 'Z')) {
            return null;
        }
        var flag = new StringBuilder();
        code.chars().forEach(letter -> flag.appendCodePoint(REGIONAL_INDICATOR_A + letter - 'A'));
        return flag.toString();
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
     * @param country the guest's country, if known
     */
    record HistoryRow(ZonedDateTime occurredAt, UUID linkId, String linkLabel, AccessResult result, String outcome,
                      @Nullable String ip, @Nullable Country country) {

        static HistoryRow of(HistoryEvent event, ZoneId zone, String outcome, @Nullable Country country) {
            return new HistoryRow(event.occurredAt().atZoneSameInstant(zone), event.linkId(), event.linkLabel(),
                    event.result(), outcome, event.ip(), country);
        }
    }

    /**
     * A guest's country.
     *
     * @param flag its flag emoji, or null for Tor
     * @param name its name in the reader's language
     */
    record Country(@Nullable String flag, String name) {
    }
}
