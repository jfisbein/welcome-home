package welcomehome.audit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * History settings.
 *
 * @param retention       how long guests' attempts are kept
 * @param ipRetention     how long they keep the guest's IP address, which is personal data: after it, only its country
 * @param backupRetention how long the server's backups keep what the app has deleted: the app deletes no backup, it
 *                        only tells guests, so this is to match the stack's {@code BACKUP_RETENTION_DAYS}
 */
@ConfigurationProperties("welcomehome.audit")
record AuditProperties(@DefaultValue("P365D") Duration retention, @DefaultValue("P90D") Duration ipRetention,
                       @DefaultValue("P30D") Duration backupRetention) {
}
