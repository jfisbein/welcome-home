package welcomehome.links;

import java.net.URI;
import java.util.UUID;

import org.springframework.stereotype.Component;
import welcomehome.config.WelcomeHomeProperties;
import welcomehome.crypto.LinkTokenSigner;

/**
 * Builds a link's guest URL, {@code {base URL}/o/{link_id}.{mac}}. Computing it again gives the same URL
 * until the link's token_version changes.
 */
@Component
class LinkUrls {

    private final LinkTokenSigner signer;
    private final WelcomeHomeProperties app;

    LinkUrls(LinkTokenSigner signer, WelcomeHomeProperties app) {
        this.signer = signer;
        this.app = app;
    }

    URI url(UUID linkId, int tokenVersion) {
        return app.link("/o/" + signer.sign(linkId, tokenVersion));
    }
}
