package welcomehome.nuki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import welcomehome.crypto.CryptoService;
import welcomehome.crypto.DecryptionFailedException;

/**
 * Encrypts every owner's Nuki tokens again with the AEAD keyset's primary key, where an earlier key encrypted them,
 * once the application has started: the keyset changes only with a restart, so after a rotation no token needs the
 * earlier key any more, and it can leave the keyset without any owner connecting Nuki again. Not for the one-off
 * commands, which run without the web server.
 */
@Component
@ConditionalOnWebApplication
class TokenReEncryption {

    private static final Logger log = LoggerFactory.getLogger(TokenReEncryption.class);

    private final NukiConnections connections;
    private final CryptoService crypto;

    TokenReEncryption(NukiConnections connections, CryptoService crypto) {
        this.connections = connections;
        this.crypto = crypto;
    }

    @EventListener(ApplicationReadyEvent.class)
    void reEncryptAll() {
        var keyId = Long.toString(crypto.primaryKeyId());
        try {
            var reEncrypted = 0;
            var failed = 0;
            for (var ownerId : connections.ownersToCheck()) {
                try {
                    reEncrypted += connections.reEncrypt(ownerId);
                } catch (DecryptionFailedException e) {
                    // Its key left the keyset: the owner has to connect Nuki again, whatever is done here.
                    failed++;
                    log.warn("Could not decrypt the Nuki tokens of owner {} to encrypt them with key {}", ownerId,
                            keyId);
                } catch (RuntimeException e) {
                    // One owner's failure must not stop the others'; the next start tries again.
                    failed++;
                    log.warn("Could not encrypt the Nuki tokens of owner {} with key {}: {}", ownerId, keyId,
                            e.getMessage());
                }
            }
            if (reEncrypted > 0) {
                log.info("Nuki tokens encrypted again with key {}: {}", keyId, reEncrypted);
            }
            if (failed == 0) {
                log.info("Every stored Nuki token is encrypted with key {}", keyId);
            }
        } catch (RuntimeException e) {
            // Never stops the application, which works with the earlier keys meanwhile.
            log.warn("Could not encrypt the Nuki tokens again with key {}: {}", keyId, e.getMessage());
        }
    }
}
