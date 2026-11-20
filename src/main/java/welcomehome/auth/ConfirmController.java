package welcomehome.auth;

import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Where an owner confirms who they are with a passkey before a change that {@link RecentSignInFilter} guards: the
 * page signs in again, as the sign-in page does, and goes back to the page of the change. Which page is one of a
 * fixed few, never a URL from the request.
 */
@Controller
class ConfirmController {

    static final String ACCOUNT = "account";
    static final String PASSKEYS = "passkeys";

    private final Clock clock;

    ConfirmController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/settings/confirm")
    String confirm(@RequestParam(name = "for", defaultValue = ACCOUNT) String page, HttpServletRequest request,
                   Model model) {
        var back = PASSKEYS.equals(page) ? "/settings/passkeys" : "/settings/account";
        if (RecentSignIn.isRecent(request, clock)) {
            return "redirect:" + back;
        }
        model.addAttribute("back", back);
        model.addAttribute("minutes", String.valueOf(RecentSignIn.WINDOW.toMinutes()));
        return "confirm";
    }
}
