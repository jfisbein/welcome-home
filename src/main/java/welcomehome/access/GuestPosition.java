package welcomehome.access;

import org.jspecify.annotations.Nullable;

/**
 * Where a guest's browser says they are. It goes no further than deciding whether they are near the door: only the
 * distance to it is recorded.
 *
 * @param latitude  in degrees
 * @param longitude in degrees
 * @param accuracy  how far off it may be, in metres, as the browser estimates it
 */
record GuestPosition(double latitude, double longitude, double accuracy) {

    /**
     * Reads what the page sent.
     *
     * @return the position, or null if any part is missing or impossible, which counts as an unknown position
     */
    static @Nullable GuestPosition of(@Nullable Double latitude, @Nullable Double longitude,
                                      @Nullable Double accuracy) {
        if (latitude == null || longitude == null || accuracy == null
                || !(latitude >= -90 && latitude <= 90) || !(longitude >= -180 && longitude <= 180)
                || !(accuracy >= 0 && accuracy < Double.POSITIVE_INFINITY)) {
            return null;
        }
        return new GuestPosition(latitude, longitude, accuracy);
    }

    /** Never the position itself, which Spring logs requests' bodies with at DEBUG. */
    @Override
    public String toString() {
        return "GuestPosition[***]";
    }
}
