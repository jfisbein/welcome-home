package welcomehome.notifications;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
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
import welcomehome.auth.EmailVerification;
import welcomehome.db.OwnerTransactions;

/** The owner's choice of emails, and a test email to check that they arrive. */
@Controller
@RequestMapping("/notifications")
class NotificationsController {

    private static final Logger log = LoggerFactory.getLogger(NotificationsController.class);

    private final NotificationPreferenceDao preferences;
    private final OwnerTransactions ownerTransactions;
    private final Mailer mailer;
    private final MessageSource messages;
    private final EmailVerification emailVerification;

    NotificationsController(NotificationPreferenceDao preferences, OwnerTransactions ownerTransactions,
                            Mailer mailer, MessageSource messages, EmailVerification emailVerification) {
        this.preferences = preferences;
        this.ownerTransactions = ownerTransactions;
        this.mailer = mailer;
        this.messages = messages;
        this.emailVerification = emailVerification;
    }

    @GetMapping
    String show(CurrentOwner owner, Model model) {
        model.addAttribute("preferences", ownerTransactions.execute(owner.id(), () -> preferences.find(owner.id())));
        model.addAttribute("email", owner.email());
        model.addAttribute("emailConfigured", mailer.isConfigured());
        model.addAttribute("emailVerified", emailVerification.isVerified(owner.id()));
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

    /** In the owner's language, which is that of their pages. */
    @PostMapping("/test")
    String sendTest(CurrentOwner owner, Locale locale, RedirectAttributes redirect) {
        if (!emailVerification.isVerified(owner.id())) {
            redirect.addFlashAttribute("testUnverified", true);
            return "redirect:/notifications";
        }
        try {
            mailer.send(owner.email(), messages.getMessage("notifications.test-email.subject", null, locale),
                    messages.getMessage("notifications.test-email.body", null, locale) + "\n");
            redirect.addFlashAttribute("testSent", true);
        } catch (MailException | IllegalStateException e) {
            log.warn("Could not send a test email: {}", e.getMessage());
            redirect.addFlashAttribute("testFailed", true);
        }
        return "redirect:/notifications";
    }
}
