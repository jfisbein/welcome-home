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
 * @param entranceName the name of the Opener at the lock's building entrance, if the guest opened that rather
 *                   than the lock
 * @param result     whether the door opened, the app refused, or the lock or Nuki failed
 * @param outcome    what happened, in words
 * @param origin     where the guest was
 */
public record AttemptRow(ZonedDateTime occurredAt, UUID lockId, String lockName, UUID linkId, String linkLabel,
                         @Nullable String entranceName, AccessResult result, String outcome, Origin origin) {

    /**
     * Where a guest was: their IP address and, if Cloudflare knew it, their country; and for a door that checks
     * where its guests are, how far from it they were.
     *
     * @param ip       the guest's IP address, if still kept
     * @param flag     the country's flag emoji, or null for none or for Tor
     * @param country  the country's name in the reader's language, Tor by name, or null if unknown
     * @param distance how far from the door, in the reader's language, such as "a 3,2 km de la puerta", or null
     */
    public record Origin(@Nullable String ip, @Nullable String flag, @Nullable String country,
                         @Nullable String distance) {

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
