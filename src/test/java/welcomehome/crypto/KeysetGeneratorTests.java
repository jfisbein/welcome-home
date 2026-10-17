package welcomehome.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeysetGeneratorTests {

    @TempDir
    Path directory;

    @Test
    void writesKeysetsReadableOnlyByTheOwner() throws Exception {
        KeysetGenerator.generate(directory);

        for (var name : new String[] {KeysetGenerator.AEAD_KEYSET_FILE_NAME, KeysetGenerator.MAC_KEYSET_FILE_NAME}) {
            var permissions = Files.getPosixFilePermissions(directory.resolve(name));
            assertThat(PosixFilePermissions.toString(permissions)).as(name).isEqualTo("rw-------");
        }
    }

    @Test
    void neverOverwritesExistingKeysets() throws Exception {
        KeysetGenerator.generate(directory);
        var original = Files.readString(directory.resolve(KeysetGenerator.AEAD_KEYSET_FILE_NAME));

        assertThatThrownBy(() -> KeysetGenerator.generate(directory)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(directory.resolve(KeysetGenerator.AEAD_KEYSET_FILE_NAME))).isEqualTo(original);
    }
}
