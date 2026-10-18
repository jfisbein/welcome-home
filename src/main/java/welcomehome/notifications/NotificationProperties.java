package welcomehome.notifications;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Owners' emails. They are sent only when both the SMTP server ({@code spring.mail.*}) and {@code from} are set.
 *
 * @param from                the sender, such as {@code welcome-home <avisos@example.com>}; it must be an
 *                            address the SMTP service accepts
 * @param deniedQuietPeriod   after an email about a refused attempt with a link, no other one about that link
 *                            for this long, so that a leaked link being tried does not flood the owner
 */
@ConfigurationProperties("welcomehome.notifications")
record NotificationProperties(@Nullable String from, @DefaultValue("PT1H") Duration deniedQuietPeriod) {
}
