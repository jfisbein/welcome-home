package welcomehome.audit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * History settings.
 *
 * @param retention   how long guests' attempts are kept
 * @param ipRetention how long they keep the guest's IP address, which is personal data: after it, only its country
 */
@ConfigurationProperties("welcomehome.audit")
record AuditProperties(@DefaultValue("P365D") Duration retention, @DefaultValue("P90D") Duration ipRetention) {
}
