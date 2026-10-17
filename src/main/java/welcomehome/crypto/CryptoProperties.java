package welcomehome.crypto;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the keysets are. In production they are Docker secrets, set with {@code AEAD_KEYSET_FILE} and
 * {@code MAC_KEYSET_FILE}.
 *
 * @param aeadKeysetFile the AES-256-GCM keyset that encrypts Nuki API tokens
 * @param macKeysetFile  the HMAC-SHA256 keyset that signs access links
 */
@ConfigurationProperties("welcomehome.crypto")
record CryptoProperties(Path aeadKeysetFile, Path macKeysetFile) {
}
