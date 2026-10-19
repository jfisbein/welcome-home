package welcomehome.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import welcomehome.IntegrationTest;

@IntegrationTest
class AccessOutcomeTests {

    @Autowired
    MessageSource messages;

    @ParameterizedTest
    @EnumSource(AccessOutcome.class)
    void everyOutcomeIsFoundByItsCodeAndHasALabelInTheHistory(AccessOutcome outcome) {
        assertThat(AccessOutcome.ofCode(outcome.code())).contains(outcome);
        assertThat(messages.getMessage("outcome." + outcome.code(), null, Locale.of("es"))).isNotBlank();
    }

    @Test
    void codesThatNoOutcomeHasAreNotFound() {
        assertThat(AccessOutcome.ofCode("nonsense")).isEmpty();
        assertThat(AccessOutcome.ofCode(null)).isEmpty();
    }

    @Test
    void codesAreThoseAlreadyStoredInHistories() {
        assertThat(AccessOutcome.values()).extracting(AccessOutcome::code).containsExactly(
                "opened", "not-confirmed", "invalid", "too-many-attempts", "revoked", "not-yet-valid", "expired",
                "unavailable", "nuki-error", "lock-failed", "night-mode");
    }
}
