package welcomehome.access;

import java.util.Map;

/**
 * A door on a guest's page, with its own slide to open: the link's lock, or the building entrance before it.
 * Public only for the page's template to read.
 *
 * @param id        the slider's id in the page
 * @param door      what the page sends to open it, as {@link Door} reads it
 * @param opener    whether it is an Opener, which opens an entrance for a few seconds, rather than a lock
 * @param available whether it can be opened at all: its lock is active and its owner's Nuki connection is OK
 * @param messages  what the guest reads after sliding, by outcome code, in the guest's language
 */
public record GuestDoor(String id, String door, boolean opener, boolean available, Map<String, String> messages) {
}
