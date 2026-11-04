package welcomehome.nuki;

import org.jspecify.annotations.Nullable;

/**
 * Where a lock is: a latitude and a longitude in degrees, as GPS gives them.
 *
 * @param latitude  from -90 (south) to 90 (north)
 * @param longitude from -180 (west) to 180 (east)
 */
public record Coordinates(double latitude, double longitude) {

    /**
     * Coordinates within their ranges.
     *
     * @throws IllegalArgumentException if either is out of its range
     */
    public Coordinates {
        if (!(latitude >= -90 && latitude <= 90) || !(longitude >= -180 && longitude <= 180)) {
            throw new IllegalArgumentException("Not a place on Earth: " + latitude + ", " + longitude);
        }
    }

    /**
     * Where Nuki says a device is, if it says: the owner sets it in the Nuki app. 0, 0, in the sea off Africa, is
     * taken for none, as it is what a device without one would report.
     *
     * @return the coordinates, or null if Nuki gives none or impossible ones
     */
    static @Nullable Coordinates ofNuki(@Nullable Double latitude, @Nullable Double longitude) {
        if (latitude == null || longitude == null || (latitude == 0 && longitude == 0)
                || !(latitude >= -90 && latitude <= 90) || !(longitude >= -180 && longitude <= 180)) {
            return null;
        }
        return new Coordinates(latitude, longitude);
    }
}
