package welcomehome.nuki;

/**
 * Nuki's numeric codes as this module's types (see the API definition at
 * https://api.nuki.io/static/swagger/swagger.json).
 */
final class NukiCodes {

    private static final int OPENER = 2;

    private NukiCodes() {
    }

    static LockStatus status(int nukiState) {
        return switch (nukiState) {
            case 1 -> LockStatus.LOCKED;
            case 3, 6 -> LockStatus.UNLOCKED;
            case 5 -> LockStatus.UNLATCHED;
            case 2, 4, 7 -> LockStatus.MOVING;
            case 254 -> LockStatus.MOTOR_BLOCKED;
            default -> LockStatus.UNKNOWN;
        };
    }

    /** An Opener's states are its own: it locks nothing, and opens the entrance only for a few seconds. */
    static LockStatus openerStatus(int nukiState) {
        return switch (nukiState) {
            case 1 -> LockStatus.READY;
            case 3 -> LockStatus.RING_TO_OPEN;
            case 5, 7 -> LockStatus.OPENING;
            default -> LockStatus.UNKNOWN;
        };
    }

    /** Smart Locks of every generation, and the Smart Door, are smart locks; only an Opener is not. */
    static DeviceType deviceType(int nukiType) {
        return nukiType == OPENER ? DeviceType.OPENER : DeviceType.SMART_LOCK;
    }

    static LockActionState actionState(int nukiState) {
        return switch (nukiState) {
            case 0 -> LockActionState.SUCCEEDED;
            case 1 -> LockActionState.MOTOR_BLOCKED;
            case 10 -> LockActionState.REJECTED_NIGHT_MODE;
            default -> LockActionState.FAILED;
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

    static LockAction action(int nukiAction) {
        return switch (nukiAction) {
            case 1 -> LockAction.UNLOCK;
            case 2 -> LockAction.LOCK;
            case 3 -> LockAction.UNLATCH;
            case 240 -> LockAction.DOOR_OPENED;
            case 241 -> LockAction.DOOR_CLOSED;
            default -> LockAction.OTHER;
        };
    }
}
