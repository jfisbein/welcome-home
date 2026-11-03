package welcomehome.preferences;

import java.time.Duration;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.auth.CurrentOwner;
import welcomehome.config.Languages;

/**
 * The owner's preferences, a section of configuration: their language, for their pages and their emails, and
 * the color theme of this browser.
 */
@Controller
class PreferencesController {

    /** How long the theme cookie lasts; each choice starts it again. */
    static final Duration THEME_COOKIE_AGE = Duration.ofDays(365);

    private final OwnerLanguageDao owners;
    private final boolean secureCookies;

    PreferencesController(OwnerLanguageDao owners,
                          @Value("${server.servlet.session.cookie.secure:true}") boolean secureCookies) {
        this.owners = owners;
        this.secureCookies = secureCookies;
    }

    @GetMapping("/settings/preferences")
    String page(Model model) {
        model.addAttribute("languages", Languages.SUPPORTED.stream()
                .map(language -> new LanguageOption(language.toLanguageTag(), Languages.nameOf(language)))
                .toList());
        return "preferences";
    }

    @PutMapping("/settings/language")
    String changeLanguage(CurrentOwner owner, @RequestParam(defaultValue = "") String language,
                          RedirectAttributes redirect) {
        var chosen = Languages.of(language).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));
        owners.setLanguage(owner.id(), chosen);
        redirect.addFlashAttribute("languageSaved", true);
        return "redirect:/settings/preferences";
    }

    /** Light or dark keeps the choice in a cookie, as the session's; automatic deletes it. */
    @PutMapping("/settings/theme")
    String changeTheme(@RequestParam(defaultValue = "") String theme, HttpServletResponse response,
                       RedirectAttributes redirect) {
        var cookie = ResponseCookie.from(Theme.COOKIE).path("/").secure(secureCookies).httpOnly(true).sameSite("Lax");
        if (theme.equals("auto")) {
            cookie.value("").maxAge(Duration.ZERO);
        } else {
            var chosen = Theme.of(theme).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));
            cookie.value(chosen.value()).maxAge(THEME_COOKIE_AGE);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
        redirect.addFlashAttribute("themeSaved", true);
        return "redirect:/settings/preferences";
    }

    /**
     * A language to choose.
     *
     * @param tag  its tag, such as {@code en}
     * @param name its name in itself, such as English
     */
    record LanguageOption(String tag, String name) {
    }
}
