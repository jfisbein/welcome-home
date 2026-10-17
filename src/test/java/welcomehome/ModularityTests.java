package welcomehome;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void modulesOnlyUseEachOthersPublicApi() {
        ApplicationModules.of(WelcomeHomeApplication.class).verify();
    }
}
