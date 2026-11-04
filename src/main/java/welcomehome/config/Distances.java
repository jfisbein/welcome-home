package welcomehome.config;

import java.text.NumberFormat;
import java.util.Locale;

/** How a distance is written for a reader: "300 m", "3,2 km", as the history and emails give a guest's. */
public final class Distances {

    private Distances() {
    }

    /**
     * A distance in the reader's language.
     *
     * @param metres the distance, in metres
     * @param locale the reader's language
     * @return metres under a kilometre, kilometres with one decimal above it
     */
    public static String of(int metres, Locale locale) {
        var format = NumberFormat.getNumberInstance(locale);
        if (metres < 1000) {
            return format.format(metres) + " m";
        }
        format.setMaximumFractionDigits(1);
        return format.format(metres / 1000.0) + " km";
    }
}
