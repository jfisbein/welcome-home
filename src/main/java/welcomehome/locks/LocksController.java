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
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.LockProviderException;

/**
 * The owner's locks: their list, their sync with Nuki and their settings. Another owner's lock is not found.
 */
@Controller
@RequestMapping("/locks")
class LocksController {

    private static final String EDIT_VIEW = "locks/edit";

    private final LockSync lockSync;
    private final LockSettings lockSettings;
    private final LockDao locks;
    private final OwnerTransactions ownerTransactions;

    LocksController(LockSync lockSync, LockSettings lockSettings, LockDao locks, OwnerTransactions ownerTransactions) {
        this.lockSync = lockSync;
        this.lockSettings = lockSettings;
        this.locks = locks;
        this.ownerTransactions = ownerTransactions;
    }

    @GetMapping
    String locks(CurrentOwner owner, Model model) {
        model.addAttribute("locks", ownerTransactions.execute(owner.id(), () -> locks.findAll(owner.id())));
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
                case LOCK_NOT_FOUND, UNAVAILABLE -> "unavailable";
            });
        }
        return "redirect:/locks";
    }

    @GetMapping("/{lockId}")
    String edit(CurrentOwner owner, @PathVariable UUID lockId, Model model) {
        var lock = lockSettings.find(owner.id(), lockId).orElseThrow(LocksController::notFound);
        model.addAttribute("lock", lock);
        model.addAttribute("name", lock.name());
        model.addAttribute("timeZone", lock.timeZone());
        model.addAttribute("timeZones", LockSettings.TIME_ZONES);
        return EDIT_VIEW;
    }

    @PatchMapping("/{lockId}")
    String update(CurrentOwner owner, @PathVariable UUID lockId, @RequestParam(defaultValue = "") String name,
                  @RequestParam(defaultValue = "") String timeZone, Model model, HttpServletResponse response,
                  RedirectAttributes redirect) {
        var lock = lockSettings.find(owner.id(), lockId).orElseThrow(LocksController::notFound);
        var normalizedName = LockSettings.normalizeName(name);
        var validTimeZone = LockSettings.isTimeZone(timeZone);
        if (normalizedName.isEmpty() || !validTimeZone) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("lock", lock);
            model.addAttribute("name", name);
            model.addAttribute("timeZone", timeZone);
            model.addAttribute("timeZones", LockSettings.TIME_ZONES);
            model.addAttribute("nameError", normalizedName.isEmpty());
            model.addAttribute("timeZoneError", !validTimeZone);
            return EDIT_VIEW;
        }
        if (!lockSettings.update(owner.id(), lockId, normalizedName.get(), timeZone)) {
            throw notFound();
        }
        redirect.addFlashAttribute("updated", normalizedName.get());
        return "redirect:/locks";
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
