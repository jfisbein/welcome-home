package welcomehome.nuki;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Nuki's time zone ids, as listed in Nuki's "Key API Concepts" (https://docs.nuki.io/guide/overview/concepts/).
 * Nuki covers each region with one zone: a lock in Madrid reports Europe/Berlin, which has the same rules.
 */
final class NukiTimeZones {

    /** Index = Nuki's timezoneId. */
    static final List<String> BY_ID = List.of(
            "Africa/Cairo", "Africa/Lagos", "Africa/Maputo", "Africa/Nairobi",
            "America/Anchorage", "America/Buenos_Aires", "America/Chicago", "America/Denver",
            "America/Halifax", "America/Los_Angeles", "America/Manaus", "America/Mexico_City",
            "America/New_York", "America/Phoenix", "America/Regina", "America/Santiago",
            "America/Sao_Paulo", "America/St_Johns",
            "Asia/Bangkok", "Asia/Dubai", "Asia/Hong_Kong", "Asia/Jerusalem", "Asia/Karachi",
            "Asia/Kathmandu", "Asia/Kolkata", "Asia/Riyadh", "Asia/Seoul", "Asia/Shanghai",
            "Asia/Tehran", "Asia/Tokyo", "Asia/Yangon",
            "Australia/Adelaide", "Australia/Brisbane", "Australia/Darwin", "Australia/Hobart",
            "Australia/Perth", "Australia/Sydney",
            "Europe/Berlin", "Europe/Helsinki", "Europe/Istanbul", "Europe/London", "Europe/Moscow",
            "Pacific/Auckland", "Pacific/Guam", "Pacific/Honolulu", "Pacific/Pago_Pago");

    private NukiTimeZones() {
    }

    /** The zone for a Nuki timezoneId, or null when the lock reports none (65535) or an id not in the list. */
    static @Nullable String byId(@Nullable Integer timezoneId) {
        return timezoneId != null && timezoneId >= 0 && timezoneId < BY_ID.size() ? BY_ID.get(timezoneId) : null;
    }
}
