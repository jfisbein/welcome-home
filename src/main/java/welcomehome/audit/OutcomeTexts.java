package welcomehome.audit;

import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * What an event's reason code means to the owner, in their language, for the history and the home page.
 */
@Component
class OutcomeTexts {

    private final MessageSource messages;

    OutcomeTexts(MessageSource messages) {
        this.messages = messages;
    }

    /** @return the outcome in words; a code no outcome has is shown as it is */
    String describe(@Nullable String reason, Locale locale) {
        return AccessOutcome.ofCode(reason)
                .map(outcome -> messages.getMessage("outcome." + outcome.code(), null, locale))
                .orElse(reason == null ? "" : reason);
    }
}
