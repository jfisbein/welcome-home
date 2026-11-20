package welcomehome;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * What Spring Modulith checks of the modules, the packages under {@code welcomehome}: that each uses the others'
 * public types only, and that none depend on each other in a cycle. Which module may use which is not declared, so
 * a new dependency between two of them passes.
 */
class ModularityTests {

    @Test
    void modulesOnlyUseEachOthersPublicApiAndFormNoCycle() {
        ApplicationModules.of(WelcomeHomeApplication.class).verify();
    }
}
