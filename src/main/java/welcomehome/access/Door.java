package welcomehome.access;

import org.jspecify.annotations.Nullable;

/**
 * Which door a guest opens with a link: the link's own lock, or the Opener at that lock's building entrance.
 */
enum Door {

    /** The link's lock, a smart lock or an Opener. */
    LOCK("lock"),
    /** The Opener its owner chose as the building entrance of the link's lock. */
    ENTRANCE("entrance");

    private final String code;

    Door(String code) {
        this.code = code;
    }

    /** What the guest page sends to open this door. */
    String code() {
        return code;
    }

    /** The door a request names: the entrance by its code, and the lock otherwise, as before entrances existed. */
    static Door of(@Nullable String code) {
        return ENTRANCE.code.equals(code) ? ENTRANCE : LOCK;
    }
}
