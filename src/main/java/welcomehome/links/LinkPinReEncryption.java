package welcomehome.links;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import welcomehome.crypto.CryptoService;
import welcomehome.crypto.DecryptionFailedException;

/**
 * Encrypts every link's PIN again with the AEAD keyset's primary key, where an earlier key encrypted it, once the
 * application has started, as is done for Nuki tokens: after a rotation no PIN needs the earlier key any more, and it
 * can leave the keyset. Not for the one-off commands, which run without the web server.
 */
@Component
@ConditionalOnWebApplication
class LinkPinReEncryption {

    private static final Logger log = LoggerFactory.getLogger(LinkPinReEncryption.class);

    private final LinkPins pins;
    private final CryptoService crypto;

    LinkPinReEncryption(LinkPins pins, CryptoService crypto) {
        this.pins = pins;
        this.crypto = crypto;
    }

    @EventListener(ApplicationReadyEvent.class)
    void reEncryptAll() {
        var keyId = Long.toString(crypto.primaryKeyId());
        try {
            var reEncrypted = 0;
            var failed = 0;
            for (var ownerId : pins.owners()) {
                try {
                    reEncrypted += pins.reEncrypt(ownerId);
                } catch (DecryptionFailedException e) {
                    failed++;
                    log.warn("Could not decrypt the link PINs of owner {} to encrypt them with key {}", ownerId, keyId);
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Could not encrypt the link PINs of owner {} with key {}: {}", ownerId, keyId,
                            e.getMessage());
                }
            }
            if (reEncrypted > 0) {
                log.info("Link PINs encrypted again with key {}: {}", keyId, reEncrypted);
            }
            if (failed == 0) {
                log.info("Every stored link PIN is encrypted with key {}", keyId);
            }
        } catch (RuntimeException e) {
            log.warn("Could not encrypt the link PINs again with key {}: {}", keyId, e.getMessage());
        }
    }
}
