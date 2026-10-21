package welcomehome.nuki;

/** Where an action in a lock's log came from, as Nuki tells it. */
public enum LockTrigger {

    /** The lock itself, or the system: also how Nuki has logged unlatches through its Web API and the app. */
    SYSTEM,
    /** By hand, turning the knob or the key. */
    MANUAL,
    /** The lock's button. */
    BUTTON,
    /** An automatic action, such as Auto Unlock. */
    AUTOMATIC,
    /** The Nuki Web API, by Nuki's documentation; its unlatches have been logged as {@link #SYSTEM} so far. */
    WEB,
    /** The Nuki app. */
    APP,
    /** Auto Lock. */
    AUTO_LOCK,
    /** An accessory, such as a keypad or a fob. */
    ACCESSORY,
    /** Anything else. */
    OTHER
}
