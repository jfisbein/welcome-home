package welcomehome.audit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * History settings.
 *
 * @param retention how long guests' attempts are kept: they hold IP addresses, which are personal data
 */
@ConfigurationProperties("welcomehome.audit")
record AuditProperties(@DefaultValue("365d") Duration retention) {
}
