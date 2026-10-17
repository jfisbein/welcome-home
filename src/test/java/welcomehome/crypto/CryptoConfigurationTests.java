package welcomehome.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.Mac;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CryptoConfigurationTests {

    @TempDir
    static Path keysets;

    static String aeadKeysetFile;
    static String macKeysetFile;

    final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CryptoConfiguration.class);

    @BeforeAll
    static void generateKeysets() throws Exception {
        KeysetGenerator.generate(keysets);
        aeadKeysetFile = "welcomehome.crypto.aead-keyset-file=" + keysets.resolve(KeysetGenerator.AEAD_KEYSET_FILE_NAME);
        macKeysetFile = "welcomehome.crypto.mac-keyset-file=" + keysets.resolve(KeysetGenerator.MAC_KEYSET_FILE_NAME);
    }

    @Test
    void startsWithBothKeysets() {
        context.withPropertyValues(aeadKeysetFile, macKeysetFile)
                .run(started -> assertThat(started)
                        .hasNotFailed()
                        .hasSingleBean(Aead.class)
                        .hasSingleBean(Mac.class));
    }

    @Test
    void doesNotStartWithoutTheAeadKeyset() {
        context.withPropertyValues(macKeysetFile)
                .run(started -> assertThat(started).getFailure()
                        .rootCause()
                        .hasMessage("No keyset configured: set AEAD_KEYSET_FILE"));
    }

    @Test
    void doesNotStartWithoutTheMacKeyset() {
        context.withPropertyValues(aeadKeysetFile, "welcomehome.crypto.mac-keyset-file=")
                .run(started -> assertThat(started).getFailure()
                        .rootCause()
                        .hasMessage("No keyset configured: set MAC_KEYSET_FILE"));
    }

    @Test
    void doesNotStartWhenAKeysetFileIsMissing() {
        context.withPropertyValues(aeadKeysetFile, "welcomehome.crypto.mac-keyset-file=/nonexistent/mac-keyset.json")
                .run(started -> assertThat(started).getFailure()
                        .rootCause()
                        .hasMessageStartingWith("Keyset file not found or not readable: /nonexistent/mac-keyset.json"));
    }
}
