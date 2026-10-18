package welcomehome.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.CurrentOwner;
import welcomehome.db.OwnerTransactions;

/** The owner's choice of emails, and a test email to check that they arrive. */
@Controller
@RequestMapping("/notifications")
class NotificationsController {

    private static final Logger log = LoggerFactory.getLogger(NotificationsController.class);

    private final NotificationPreferenceDao preferences;
    private final OwnerTransactions ownerTransactions;
    private final Mailer mailer;

    NotificationsController(NotificationPreferenceDao preferences, OwnerTransactions ownerTransactions,
                            Mailer mailer) {
        this.preferences = preferences;
        this.ownerTransactions = ownerTransactions;
        this.mailer = mailer;
    }

    @GetMapping
    String show(CurrentOwner owner, Model model) {
        model.addAttribute("preferences", ownerTransactions.execute(owner.id(), () -> preferences.find(owner.id())));
        model.addAttribute("email", owner.email());
        model.addAttribute("emailConfigured", mailer.isConfigured());
        return "notifications/preferences";
    }

    /** An unchecked box is not sent at all, hence the defaults. */
    @PutMapping
    String save(CurrentOwner owner, @RequestParam(defaultValue = "false") boolean opened,
                @RequestParam(defaultValue = "false") boolean failed, @RequestParam(defaultValue = "false") boolean denied,
                @RequestParam(defaultValue = "false") boolean disconnected, RedirectAttributes redirect) {
        var chosen = new NotificationPreferences(opened, failed, denied, disconnected);
        ownerTransactions.run(owner.id(), () -> preferences.save(owner.id(), chosen));
        redirect.addFlashAttribute("saved", true);
        return "redirect:/notifications";
    }

    @PostMapping("/test")
    String sendTest(CurrentOwner owner, RedirectAttributes redirect) {
        try {
            mailer.send(owner.email(), "Email de prueba de welcome-home",
                    "Si lees esto, los avisos de welcome-home te llegan a esta dirección.\n");
            redirect.addFlashAttribute("testSent", true);
        } catch (MailException | IllegalStateException e) {
            log.warn("Could not send a test email: {}", e.getMessage());
            redirect.addFlashAttribute("testFailed", true);
        }
        return "redirect:/notifications";
    }
}
