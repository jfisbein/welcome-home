package welcomehome.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import welcomehome.config.Languages;

/** The owner's language, for their pages and their emails; a section of configuration. */
@Controller
class LanguageController {

    private final OwnerAccountDao owners;

    LanguageController(OwnerAccountDao owners) {
        this.owners = owners;
    }

    @GetMapping("/language")
    String page() {
        return "language";
    }

    @PutMapping("/language")
    String change(CurrentOwner owner, @RequestParam(defaultValue = "") String language, RedirectAttributes redirect) {
        var chosen = Languages.of(language).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));
        owners.setLanguage(owner.id(), chosen);
        redirect.addFlashAttribute("languageSaved", true);
        return "redirect:/language";
    }
}
