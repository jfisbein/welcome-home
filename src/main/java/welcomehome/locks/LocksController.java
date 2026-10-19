package welcomehome.locks;

import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
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
 * The owner's locks: their list, their sync with Nuki, and each lock's page with its live state and its
 * settings. Another owner's lock is not found.
 */
@Controller
@RequestMapping("/locks")
class LocksController {

    private static final String EDIT_VIEW = "locks/edit";

    private final Locks locks;
    private final LockSync lockSync;
    private final LockSettings lockSettings;
    private final LockProvider lockProvider;

    LocksController(Locks locks, LockSync lockSync, LockSettings lockSettings, LockProvider lockProvider) {
        this.locks = locks;
        this.lockSync = lockSync;
        this.lockSettings = lockSettings;
        this.lockProvider = lockProvider;
    }

    @GetMapping
    String locks(CurrentOwner owner, Model model) {
        model.addAttribute("locks", locks.findAll(owner.id()));
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
        return editView(owner, lock, model);
    }

    @PatchMapping("/{lockId}")
    String update(CurrentOwner owner, @PathVariable UUID lockId, @RequestParam(defaultValue = "") String name,
                  @RequestParam(defaultValue = "") String timeZone,
                  @RequestParam(defaultValue = "false") boolean relockingHandledElsewhere,
                  Model model, HttpServletResponse response, RedirectAttributes redirect) {
        var lock = locks.find(owner.id(), lockId).orElseThrow(LocksController::notFound);
        var normalizedName = LockSettings.normalizeName(name);
        var validTimeZone = LockSettings.isTimeZone(timeZone);
        if (normalizedName.isEmpty() || !validTimeZone) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("name", name);
            model.addAttribute("timeZone", timeZone);
            model.addAttribute("relockingHandledElsewhere", relockingHandledElsewhere);
            model.addAttribute("nameError", normalizedName.isEmpty());
            model.addAttribute("timeZoneError", !validTimeZone);
            return editView(owner, lock, model);
        }
        if (!lockSettings.update(owner.id(), lockId, normalizedName.get(), timeZone, relockingHandledElsewhere)) {
            throw notFound();
        }
        redirect.addFlashAttribute("updated", normalizedName.get());
        return "redirect:/locks";
    }

    /** The lock's page, with its live state from Nuki when it can be read: Nuki failing never hides the settings. */
    private String editView(CurrentOwner owner, OwnerLock lock, Model model) {
        model.addAttribute("lock", lock);
        model.addAttribute("timeZones", LockSettings.TIME_ZONES);
        if (lock.active()) {
            try {
                model.addAttribute("state", lockProvider.getState(owner.id(), lock.nukiSmartlockId()));
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
