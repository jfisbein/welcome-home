package welcomehome.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.config.Languages;

/** The owner's language, for their pages and their emails; chosen on the panel. */
@Controller
class LanguageController {

    private final OwnerAccountDao owners;

    LanguageController(OwnerAccountDao owners) {
        this.owners = owners;
    }

    @PutMapping("/language")
    String change(CurrentOwner owner, @RequestParam(defaultValue = "") String language, RedirectAttributes redirect) {
        var chosen = Languages.of(language).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));
        owners.setLanguage(owner.id(), chosen);
        redirect.addFlashAttribute("languageSaved", true);
        return "redirect:/";
    }
}
