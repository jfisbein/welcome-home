package welcomehome.audit;

import java.util.Arrays;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * What a guest's attempt to open a door came to. Its code is what the guest page explains and what the owner's
 * history stores, so codes never change once released. What each outcome means is said here, once: what happened
 * at the door ({@link AccessResult}) and what it is to the owner ({@link News}); the history and the emails read
 * it from here.
 */
public enum AccessOutcome {

    /** The lock's log shows the unlatch. */
    OPENED("opened", AccessResult.OK, News.OPENED),
    /**
     * Nuki accepted the unlatch, or did not answer it, and the lock's log did not show it in time: the door may
     * have opened.
     */
    NOT_CONFIRMED("not-confirmed", AccessResult.OK, News.FAILED),
    /** No such link, or its MAC is wrong. Never recorded: there is no owner to record it for. */
    INVALID("invalid", AccessResult.DENIED, News.DENIED),
    /** Rate limited, by IP address or by link, or the link's wrong PINs are spent for a while. */
    TOO_MANY_ATTEMPTS("too-many-attempts", AccessResult.DENIED, News.DENIED),
    /** The owner revoked the link. */
    REVOKED("revoked", AccessResult.DENIED, News.DENIED),
    /** Before the link's dates. */
    NOT_YET_VALID("not-yet-valid", AccessResult.DENIED, News.DENIED),
    /** After the link's dates. */
    EXPIRED("expired", AccessResult.DENIED, News.DENIED),
    /** Within the link's dates, but at a time its schedule leaves out. */
    OUTSIDE_SCHEDULE("outside-schedule", AccessResult.DENIED, News.DENIED),
    /** The lock is inactive, Nuki is not connected, or Nuki refuses the owner's token: the owner must act. */
    UNAVAILABLE("unavailable", AccessResult.DENIED, News.FAILED),
    /** Nuki refused or failed the unlatch before the lock was asked: trying again later may work. */
    NUKI_ERROR("nuki-error", AccessResult.ERROR, News.FAILED),
    /** The lock tried and failed, for example with its motor blocked. */
    LOCK_FAILED("lock-failed", AccessResult.ERROR, News.FAILED),
    /** The lock is in night mode and refused to open. */
    NIGHT_MODE("night-mode", AccessResult.ERROR, News.FAILED),
    /** Nuki refused the unlatch: the owner's Nuki token lacks the permission to operate the lock. */
    MISSING_PERMISSION("missing-permission", AccessResult.ERROR, News.FAILED),
    /**
     * The door checks where its guests are, and this one seemed far from it, or its position was unknown: the
     * guest may open by confirming they are in front of it. Nothing was asked of the lock.
     */
    CONFIRM_LOCATION("confirm-location", AccessResult.DENIED, News.NONE),
    /** The door checks where its guests are, refuses those far from it, and this one was. */
    TOO_FAR("too-far", AccessResult.DENIED, News.DENIED),
    /** The link has a PIN and none came with the slide. The page asks for it first, so only other clients get here. */
    PIN_REQUIRED("pin-required", AccessResult.DENIED, News.NONE),
    /** The link has a PIN and this was not it. */
    WRONG_PIN("wrong-pin", AccessResult.DENIED, News.DENIED),
    /**
     * The link has been given so many wrong PINs, this one perhaps the last, that it opens no more until its owner
     * edits it or gives it a new URL.
     */
    PIN_LOCKED("pin-locked", AccessResult.DENIED, News.DENIED),
    /**
     * The door opens only for a guest whose position is known and near, and this one's was unknown or too vague to
     * tell.
     */
    LOCATION_REQUIRED("location-required", AccessResult.DENIED, News.DENIED);

    /**
     * What an attempt is to the link's owner, which is what an email about it says. Another question than
     * {@link AccessResult}, which says what happened at the door, so the two need not agree: a door that is not
     * available was refused by the app, and yet is a failure to its owner, who is the one to act; and an opening
     * the lock did not confirm may have opened the door, and yet is told as a failure.
     */
    public enum News {
        /** A guest opened the door. */
        OPENED,
        /** A guest with a link that should have opened could not, or it is not known that they did. */
        FAILED,
        /** A guest's link was refused. */
        DENIED,
        /** Nothing to tell: the guest was asked for something, and their mistake was caught. */
        NONE
    }

    private final String code;
    private final AccessResult result;
    private final News news;

    AccessOutcome(String code, AccessResult result, News news) {
        this.code = code;
        this.result = result;
        this.news = news;
    }

    /**
     * The short code for this outcome, such as {@code expired} or {@code nuki-error}.
     *
     * @return the code
     */
    public String code() {
        return code;
    }

    /**
     * Whether the door opened, the app refused, or the lock or Nuki failed.
     *
     * @return the result
     */
    public AccessResult result() {
        return result;
    }

    /**
     * What the attempt is to the link's owner.
     *
     * @return the news it is
     */
    public News news() {
        return news;
    }

    /**
     * Finds the outcome with a code.
     *
     * @param code a code as {@link #code()} gives it, or {@code null}
     * @return the outcome, or empty if no outcome has that code
     */
    public static Optional<AccessOutcome> ofCode(@Nullable String code) {
        return Arrays.stream(values()).filter(outcome -> outcome.code.equals(code)).findFirst();
    }
}
