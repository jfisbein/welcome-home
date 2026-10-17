package welcomehome.locks;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.CurrentOwner;
import welcomehome.db.OwnerTransactions;
import welcomehome.nuki.LockProviderException;

/**
 * The owner's locks and their sync with Nuki.
 */
@Controller
@RequestMapping("/locks")
class LocksController {

    private final LockSync lockSync;
    private final LockDao locks;
    private final OwnerTransactions ownerTransactions;

    LocksController(LockSync lockSync, LockDao locks, OwnerTransactions ownerTransactions) {
        this.lockSync = lockSync;
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
}
