package welcomehome.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes invitations and passkey recovery links once a day, when they expired more than
 * {@link #KEPT_AFTER_EXPIRY} ago, used or not: long enough to look into one that was misused, with the database
 * at hand, as nothing shows them once they can no longer be used.
 */
@Component
class InvitationPurgeJob {

    static final Duration KEPT_AFTER_EXPIRY = Duration.ofDays(30);

    private static final Logger log = LoggerFactory.getLogger(InvitationPurgeJob.class);

    private final InvitationDao invitations;
    private final Clock clock;

    InvitationPurgeJob(InvitationDao invitations, Clock clock) {
        this.clock = clock;
        this.invitations = invitations;
    }

    @Scheduled(cron = "${welcomehome.admin.purge-cron}")
    void purge() {
        var cutoff = clock.instant().minus(KEPT_AFTER_EXPIRY);
        var purged = invitations.deleteExpiredBefore(cutoff);
        if (purged > 0) {
            log.info("Purged {} invitations that expired before {}", purged, cutoff);
        }
    }
}
