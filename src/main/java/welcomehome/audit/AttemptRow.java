package welcomehome.audit;

import java.time.ZonedDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A guest's attempt as the home page and the history show it, in one table alike.
 *
 * @param occurredAt when, in the lock's time zone
 * @param lockId     the lock
 * @param lockName   the lock's name
 * @param linkId     the link the guest used
 * @param linkLabel  the link's label
 * @param result     whether the door opened, the app refused, or the lock or Nuki failed
 * @param outcome    what happened, in words
 * @param origin     where the guest was
 */
public record AttemptRow(ZonedDateTime occurredAt, UUID lockId, String lockName, UUID linkId, String linkLabel,
                         AccessResult result, String outcome, Origin origin) {

    /**
     * Where a guest was: their IP address and, if Cloudflare knew it, their country.
     *
     * @param ip      the guest's IP address, if still kept
     * @param flag    the country's flag emoji, or null for none or for Tor
     * @param country the country's name in the reader's language, Tor by name, or null if unknown
     */
    public record Origin(@Nullable String ip, @Nullable String flag, @Nullable String country) {

        /**
         * What the flag's tooltip says.
         *
         * @return the country and the IP address, whichever are known
         */
        public String description() {
            if (country == null) {
                return ip == null ? "" : ip;
            }
            return ip == null ? country : country + " · " + ip;
        }
    }
}
