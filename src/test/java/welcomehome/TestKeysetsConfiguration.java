package welcomehome;

import java.nio.file.Files;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import welcomehome.crypto.KeysetGenerator;

/**
 * Fresh keysets for each test context, in a temporary directory: no key is ever committed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestKeysetsConfiguration {

    @Bean
    DynamicPropertyRegistrar keysetProperties() throws Exception {
        var directory = Files.createTempDirectory("welcome-home-keysets");
        KeysetGenerator.generate(directory);
        var aeadKeyset = directory.resolve(KeysetGenerator.AEAD_KEYSET_FILE_NAME);
        var macKeyset = directory.resolve(KeysetGenerator.MAC_KEYSET_FILE_NAME);
        directory.toFile().deleteOnExit();
        aeadKeyset.toFile().deleteOnExit();
        macKeyset.toFile().deleteOnExit();

        return registry -> {
            registry.add("welcomehome.crypto.aead-keyset-file", aeadKeyset::toString);
            registry.add("welcomehome.crypto.mac-keyset-file", macKeyset::toString);
        };
    }
}
