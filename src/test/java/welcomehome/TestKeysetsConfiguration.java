package welcomehome;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import welcomehome.crypto.KeysetGenerator;
import welcomehome.crypto.KeysetRotator;

/**
 * Fresh keysets for each test context, in a temporary directory: no key is ever committed. The AEAD keyset is rotated
 * once, as production's is after a rotation, and the keyset from before is kept next to it, under
 * {@link #AEAD_KEYSET_BEFORE_ROTATION}, for tests that need tokens of an earlier key.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestKeysetsConfiguration {

    /** The AEAD keyset before its rotation, next to the one the application uses: its only key is no longer primary. */
    public static final String AEAD_KEYSET_BEFORE_ROTATION = "aead-keyset-before-rotation.json";

    @Bean
    DynamicPropertyRegistrar keysetProperties() throws Exception {
        var directory = Files.createTempDirectory("welcome-home-keysets");
        KeysetGenerator.generate(directory);
        var aeadKeyset = directory.resolve(KeysetGenerator.AEAD_KEYSET_FILE_NAME);
        var macKeyset = directory.resolve(KeysetGenerator.MAC_KEYSET_FILE_NAME);
        var beforeRotation = directory.resolve(AEAD_KEYSET_BEFORE_ROTATION);
        Files.copy(aeadKeyset, beforeRotation, StandardCopyOption.COPY_ATTRIBUTES);
        KeysetRotator.rotate(aeadKeyset);
        directory.toFile().deleteOnExit();
        aeadKeyset.toFile().deleteOnExit();
        macKeyset.toFile().deleteOnExit();
        beforeRotation.toFile().deleteOnExit();

        return registry -> {
            registry.add("welcomehome.crypto.aead-keyset-file", aeadKeyset::toString);
            registry.add("welcomehome.crypto.mac-keyset-file", macKeyset::toString);
        };
    }
}
