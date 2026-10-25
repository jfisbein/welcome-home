package welcomehome;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Configuration's entry point; its sections live under {@code /settings} in the modules they belong to. The
 * sections' addresses from before they moved there still lead to them, since emails already sent link there.
 */
@Controller
class SettingsController {

    /** Configuration has one page per section; the menu leads to the first. */
    @GetMapping("/settings")
    String settings() {
        return "redirect:/settings/nuki";
    }

    @GetMapping("/nuki/connection")
    RedirectView nuki() {
        return moved("/settings/nuki");
    }

    @GetMapping("/notifications")
    RedirectView notifications() {
        return moved("/settings/notifications");
    }

    @GetMapping("/passkeys")
    RedirectView passkeys() {
        return moved("/settings/passkeys");
    }

    private static RedirectView moved(String path) {
        var redirect = new RedirectView(path, true);
        redirect.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        return redirect;
    }
}
