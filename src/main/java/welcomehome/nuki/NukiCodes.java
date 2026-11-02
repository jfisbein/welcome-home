package welcomehome.nuki;

/**
 * Nuki's numeric codes as this module's types (see the API definition at
 * https://api.nuki.io/static/swagger/swagger.json).
 */
final class NukiCodes {

    private static final int OPENER = 2;

    private NukiCodes() {
    }

    static LockPosition position(int nukiState) {
        return switch (nukiState) {
            case 1 -> LockPosition.LOCKED;
            case 3, 6 -> LockPosition.UNLOCKED;
            case 5 -> LockPosition.UNLATCHED;
            case 2, 4, 7 -> LockPosition.MOVING;
            case 254 -> LockPosition.MOTOR_BLOCKED;
            default -> LockPosition.UNKNOWN;
        };
    }

    /** An Opener's states are its own: it locks nothing, and opens the entrance only for a few seconds. */
    static LockPosition openerPosition(int nukiState) {
        return switch (nukiState) {
            case 1 -> LockPosition.READY;
            case 3 -> LockPosition.RING_TO_OPEN;
            case 5, 7 -> LockPosition.OPENING;
            default -> LockPosition.UNKNOWN;
        };
    }

    /** Smart Locks of every generation, and the Smart Door, are smart locks; only an Opener is not. */
    static DeviceType deviceType(int nukiType) {
        return nukiType == OPENER ? DeviceType.OPENER : DeviceType.SMART_LOCK;
    }

    static ActionResult actionResult(int nukiState) {
        return switch (nukiState) {
            case 0 -> ActionResult.SUCCEEDED;
            case 1 -> ActionResult.MOTOR_BLOCKED;
            case 10 -> ActionResult.REJECTED_NIGHT_MODE;
            default -> ActionResult.FAILED;
        };
    }

    static LockTrigger trigger(int nukiTrigger) {
        return switch (nukiTrigger) {
            case 0 -> LockTrigger.SYSTEM;
            case 1 -> LockTrigger.MANUAL;
            case 2 -> LockTrigger.BUTTON;
            case 3 -> LockTrigger.AUTOMATIC;
            case 4 -> LockTrigger.WEB;
            case 5 -> LockTrigger.APP;
            case 6 -> LockTrigger.AUTO_LOCK;
            case 7 -> LockTrigger.ACCESSORY;
            default -> LockTrigger.OTHER;
        };
    }

    static LoggedAction action(int nukiAction) {
        return switch (nukiAction) {
            case 1 -> LoggedAction.UNLOCK;
            case 2 -> LoggedAction.LOCK;
            case 3 -> LoggedAction.UNLATCH;
            case 240 -> LoggedAction.DOOR_OPENED;
            case 241 -> LoggedAction.DOOR_CLOSED;
            default -> LoggedAction.OTHER;
        };
    }
}
