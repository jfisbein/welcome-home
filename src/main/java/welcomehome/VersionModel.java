package welcomehome;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** The running release (4.2.0), which the menu shows beside the app's name; from Gradle's build info. */
@ControllerAdvice
class VersionModel {

    private final @Nullable String version;

    VersionModel(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build == null ? null : build.getVersion();
    }

    /** The version, or null when the app runs without build info, as from an IDE that did not run Gradle. */
    @ModelAttribute("appVersion")
    @Nullable String appVersion() {
        return version;
    }
}
