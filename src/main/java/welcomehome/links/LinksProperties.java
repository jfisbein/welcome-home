package welcomehome.links;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long links are kept, and how many wrong PINs one takes.
 *
 * @param retention      how long a link is kept after it ended, by expiring or being cancelled: its label usually
 *                       holds a guest's name, which is personal data
 * @param pinMaxFailures how many wrong PINs in total a link takes before it refuses every attempt, until its owner
 *                       edits it or gives it a new URL; see {@link LinkPins}
 */
@ConfigurationProperties("welcomehome.links")
record LinksProperties(@DefaultValue("P365D") Duration retention, @DefaultValue("20") int pinMaxFailures) {
}
