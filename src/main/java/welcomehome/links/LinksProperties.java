package welcomehome.links;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long links are kept.
 *
 * @param retention how long a link is kept after it ended, by expiring or being cancelled: its label usually holds
 *                  a guest's name, which is personal data
 */
@ConfigurationProperties("welcomehome.links")
record LinksProperties(@DefaultValue("P365D") Duration retention) {
}
