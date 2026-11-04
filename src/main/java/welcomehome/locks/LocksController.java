package welcomehome.locks;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.CurrentOwner;
import welcomehome.nuki.LockProvider;
import welcomehome.nuki.LockProviderException;

/**
 * The owner's locks: their list with what is wrong with them, their sync with Nuki, and each lock's page with its live state and its
 * settings. Another owner's lock is not found, nor offered or accepted as an entrance.
 */
@Controller
@RequestMapping("/locks")
class LocksController {

    private static final String EDIT_VIEW = "locks/edit";

    private final Locks locks;
    private final LockSync lockSync;
    private final LockSettings lockSettings;
    private final LockProvider lockProvider;
    private final LockChecks lockChecks;

    LocksController(Locks locks, LockSync lockSync, LockSettings lockSettings, LockProvider lockProvider,
                    LockChecks lockChecks) {
        this.locks = locks;
        this.lockSync = lockSync;
        this.lockSettings = lockSettings;
        this.lockProvider = lockProvider;
        this.lockChecks = lockChecks;
    }

    @GetMapping
    String locks(CurrentOwner owner, Model model) {
        var ownerLocks = locks.findAll(owner.id());
        model.addAttribute("locks", ownerLocks);
        model.addAttribute("lockWarnings", lockChecks.warnings(owner.id(), ownerLocks));
        return "locks/locks";
    }

    @PostMapping("/sync")
    String sync(CurrentOwner owner, RedirectAttributes redirect) {
        try {
            redirect.addFlashAttribute("synced", lockSync.sync(owner.id()));
        } catch (LockProviderException e) {
            redirect.addFlashAttribute("syncError", switch (e.reason()) {
                case NOT_CONNECTED -> "not-connected";
                case ACCESS_DENIED, MISSING_PERMISSION -> "rejected";
                case LOCK_NOT_FOUND, NUKI_ERROR, NO_ANSWER -> "unavailable";
            });
        }
        return "redirect:/locks";
    }

    @GetMapping("/{lockId}")
    String edit(CurrentOwner owner, @PathVariable UUID lockId, Model model) {
        var lock = locks.find(owner.id(), lockId).orElseThrow(LocksController::notFound);
        model.addAttribute("name", lock.name());
        model.addAttribute("timeZone", lock.timeZone());
        model.addAttribute("relockingHandledElsewhere", lock.relockingHandledElsewhere());
        model.addAttribute("entranceLockId", lock.entranceLockId());
        model.addAttribute("guestInstructions", lock.guestInstructions());
        model.addAttribute("coordinates", LockSettings.format(lock.coordinates()));
        model.addAttribute("locationCheck", lock.locationCheck().name());
        model.addAttribute("locationRadius", String.valueOf(lock.locationRadiusM()));
        return editView(owner, lock, model);
    }

    @PatchMapping("/{lockId}")
    String update(CurrentOwner owner, @PathVariable UUID lockId, @RequestParam(defaultValue = "") String name,
                  @RequestParam(defaultValue = "") String timeZone,
                  @RequestParam(defaultValue = "false") boolean relockingHandledElsewhere,
                  @RequestParam(required = false) @Nullable UUID entranceLockId,
                  @RequestParam(defaultValue = "") String guestInstructions,
                  @RequestParam(required = false) @Nullable String coordinates,
                  @RequestParam(required = false) @Nullable String locationCheck,
                  @RequestParam(required = false) @Nullable String locationRadius,
                  Model model, HttpServletResponse response, RedirectAttributes redirect) {
        var lock = locks.find(owner.id(), lockId).orElseThrow(LocksController::notFound);
        var normalizedName = LockSettings.normalizeName(name);
        var validTimeZone = LockSettings.isTimeZone(timeZone);
        // Only one of the owner's Openers: another owner's lock, or a smart lock, is no entrance.
        var validEntrance = entranceLockId == null || LockSettings.entrances(lock, locks.findAll(owner.id())).stream()
                .anyMatch(entrance -> entrance.id().equals(entranceLockId));
        var normalizedInstructions = LockSettings.normalizeGuestInstructions(guestInstructions);
        // A form without the field, such as one loaded before it existed, keeps the position the lock has.
        var typedCoordinates = coordinates == null ? new LockSettings.TypedCoordinates(true, lock.coordinates())
                : LockSettings.parseCoordinates(coordinates);
        // The same for the check: a form without its fields keeps the lock's.
        var check = locationCheck == null ? Optional.of(lock.locationCheck())
                : LockSettings.parseLocationCheck(locationCheck);
        var radius = locationRadius == null ? OptionalInt.of(lock.locationRadiusM())
                : LockSettings.parseRadius(locationRadius);
        // Checking takes knowing where the door is.
        var validCheck = check.isPresent() && (check.get() == LocationCheck.OFF || typedCoordinates.coordinates() != null);
        if (normalizedName.isEmpty() || !validTimeZone || !validEntrance || normalizedInstructions.isEmpty()
                || !typedCoordinates.valid() || !validCheck || radius.isEmpty()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("name", name);
            model.addAttribute("timeZone", timeZone);
            model.addAttribute("relockingHandledElsewhere", relockingHandledElsewhere);
            model.addAttribute("entranceLockId", entranceLockId);
            model.addAttribute("guestInstructions", guestInstructions);
            model.addAttribute("coordinates", coordinates == null ? LockSettings.format(lock.coordinates()) : coordinates);
            model.addAttribute("coordinatesError", !typedCoordinates.valid());
            model.addAttribute("locationCheck", locationCheck == null ? lock.locationCheck().name() : locationCheck);
            model.addAttribute("locationCheckError", !validCheck);
            model.addAttribute("locationRadius",
                    locationRadius == null ? String.valueOf(lock.locationRadiusM()) : locationRadius);
            model.addAttribute("locationRadiusError", radius.isEmpty());
            model.addAttribute("nameError", normalizedName.isEmpty());
            model.addAttribute("timeZoneError", !validTimeZone);
            model.addAttribute("entranceError", !validEntrance);
            model.addAttribute("guestInstructionsError", normalizedInstructions.isEmpty());
            return editView(owner, lock, model);
        }
        var instructions = normalizedInstructions.get();
        if (!lockSettings.update(owner.id(), lockId, normalizedName.get(), timeZone, relockingHandledElsewhere,
                entranceLockId, instructions.isEmpty() ? null : instructions, typedCoordinates.coordinates(),
                check.get(), radius.getAsInt())) {
            throw notFound();
        }
        redirect.addFlashAttribute("updated", normalizedName.get());
        return "redirect:/locks";
    }

    /** The lock's page, with its live state from Nuki when it can be read: Nuki failing never hides the settings. */
    private String editView(CurrentOwner owner, OwnerLock lock, Model model) {
        model.addAttribute("lock", lock);
        model.addAttribute("timeZones", LockSettings.TIME_ZONES);
        model.addAttribute("maxGuestInstructionsLength", LockSettings.MAX_GUEST_INSTRUCTIONS_LENGTH);
        model.addAttribute("entrances", LockSettings.entrances(lock, locks.findAll(owner.id())));
        var saved = lock.coordinates();
        model.addAttribute("mapUrl", saved == null ? null : LockSettings.mapUrl(saved));
        model.addAttribute("locationChecks", LocationCheck.values());
        model.addAttribute("minLocationRadius", LockSettings.MIN_LOCATION_RADIUS_M);
        model.addAttribute("maxLocationRadius", LockSettings.MAX_LOCATION_RADIUS_M);
        if (lock.active()) {
            try {
                model.addAttribute("snapshot",
                        lockProvider.getSnapshot(owner.id(), lock.nukiSmartlockId()));
            } catch (LockProviderException e) {
                model.addAttribute("stateError", switch (e.reason()) {
                    case NOT_CONNECTED -> "not-connected";
                    case ACCESS_DENIED, MISSING_PERMISSION -> "rejected";
                    case LOCK_NOT_FOUND -> "not-found";
                    case NUKI_ERROR, NO_ANSWER -> "unavailable";
                });
            }
        }
        return EDIT_VIEW;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
