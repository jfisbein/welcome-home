package welcomehome.auth;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import welcomehome.config.Languages;

/**
 * What the navigation bar of every page needs to know.
 */
@ControllerAdvice
class NavigationModel {

    @ModelAttribute("isAdmin")
    boolean isAdmin(HttpServletRequest request) {
        return request.isUserInRole(OwnerUserDetailsService.ADMIN);
    }

    /** The languages an owner may choose, each named in itself. */
    @ModelAttribute("languages")
    List<LanguageOption> languages() {
        return Languages.SUPPORTED.stream()
                .map(language -> new LanguageOption(language.toLanguageTag(), Languages.nameOf(language)))
                .toList();
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
