package welcomehome.audit;

import welcomehome.links.LinkChanged.Change;

/** What an owner did with their account, as {@code owner_activity.kind} keeps it. */
enum ActivityKind {

    SIGNED_IN,
    LINK_CREATED,
    LINK_EDITED,
    LINK_NEW_URL,
    LINK_REVOKED,
    LINK_DELETED;

    static ActivityKind of(Change change) {
        return switch (change) {
            case CREATED -> LINK_CREATED;
            case EDITED -> LINK_EDITED;
            case NEW_URL -> LINK_NEW_URL;
            case REVOKED -> LINK_REVOKED;
            case DELETED -> LINK_DELETED;
        };
    }
}
