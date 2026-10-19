package welcomehome.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The link that verifies an owner's email, and asking for a new one. Opening the link only shows a button:
 * mail scanners open links, and one at a mistyped address must not verify it. The link works signed in or not.
 */
@Controller
class EmailVerificationController {

    private final EmailVerification verification;

    EmailVerificationController(EmailVerification verification) {
        this.verification = verification;
    }

    @GetMapping("/email/verify/{token}")
    String page(@PathVariable String token, Model model, HttpServletResponse response) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        model.addAttribute("noindex", true);
        var email = verification.pendingEmail(token);
        if (email.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            model.addAttribute("state", "invalid");
        } else {
            model.addAttribute("state", "pending");
            model.addAttribute("email", email.get());
            model.addAttribute("token", token);
        }
        return "email-verify";
    }

    @PostMapping("/email/verify/{token}")
    String verify(@PathVariable String token, Model model, HttpServletResponse response) {
        model.addAttribute("noindex", true);
        var email = verification.verify(token);
        if (email.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            model.addAttribute("state", "invalid");
        } else {
            model.addAttribute("state", "done");
            model.addAttribute("email", email.get());
        }
        return "email-verify";
    }

    @PostMapping("/email/verification")
    String resend(CurrentOwner owner, RedirectAttributes redirect) {
        redirect.addFlashAttribute(verification.resend(owner.id()) ? "verificationSent" : "verificationRecent", true);
        return "redirect:/";
    }
}
