package welcomehome.nuki;

/**
 * What kind of Nuki device a lock is. Nuki's Web API lists both as smartlocks, and opens both with the same
 * action: a smart lock unlatches, an Opener actuates the electric strike of a building's entrance through its
 * intercom.
 */
public enum DeviceType {
    /** A Nuki Smart Lock, or a Smart Door. */
    SMART_LOCK,
    /** A Nuki Opener: it opens a building's entrance for a few seconds, and locks nothing. */
    OPENER
}
