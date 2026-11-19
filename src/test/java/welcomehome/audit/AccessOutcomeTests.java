package welcomehome.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
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

    /**
     * What each outcome means, said once on the enum, for the history and the emails alike. The two need not agree:
     * a door that is not available was refused by the app and is a failure to its owner, who must act; an opening
     * the lock did not confirm may have opened the door and is told as a failure.
     */
    @Test
    void everyOutcomeSaysWhatHappenedAtTheDoorAndWhatItIsToTheOwner() {
        assertThat(Arrays.stream(AccessOutcome.values())
                .map(outcome -> outcome.code() + "|" + outcome.result() + "|" + outcome.news()))
                .containsExactly(
                        "opened|OK|OPENED", "not-confirmed|OK|FAILED", "invalid|DENIED|DENIED",
                        "too-many-attempts|DENIED|DENIED", "revoked|DENIED|DENIED", "not-yet-valid|DENIED|DENIED",
                        "expired|DENIED|DENIED", "outside-schedule|DENIED|DENIED", "unavailable|DENIED|FAILED",
                        "nuki-error|ERROR|FAILED", "lock-failed|ERROR|FAILED", "night-mode|ERROR|FAILED",
                        "missing-permission|ERROR|FAILED", "confirm-location|DENIED|NONE", "too-far|DENIED|DENIED",
                        "pin-required|DENIED|NONE", "wrong-pin|DENIED|DENIED", "pin-locked|DENIED|DENIED",
                        "location-required|DENIED|DENIED", "paused|DENIED|DENIED");
    }

    @Test
    void codesThatNoOutcomeHasAreNotFound() {
        assertThat(AccessOutcome.ofCode("nonsense")).isEmpty();
        assertThat(AccessOutcome.ofCode(null)).isEmpty();
    }

    @Test
    void codesStayAsHistoriesStoreThem() {
        assertThat(AccessOutcome.values()).extracting(AccessOutcome::code).containsExactly(
                "opened", "not-confirmed", "invalid", "too-many-attempts", "revoked", "not-yet-valid", "expired",
                "outside-schedule", "unavailable", "nuki-error", "lock-failed", "night-mode", "missing-permission",
                "confirm-location", "too-far", "pin-required", "wrong-pin", "pin-locked", "location-required",
                "paused");
    }
}
