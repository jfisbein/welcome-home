package welcomehome.links;

import java.net.URI;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import welcomehome.crypto.LinkTokenSigner;

/**
 * Builds a link's guest URL, {@code {base URL}/o/{link_id}.{mac}}. Computing it again gives the same URL
 * until the link's token_version changes.
 */
@Component
class LinkUrls {

    private final LinkTokenSigner signer;
    private final String baseUrl;

    LinkUrls(LinkTokenSigner signer, @Value("${welcomehome.base-url}") String baseUrl) {
        this.signer = signer;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    URI url(UUID linkId, int tokenVersion) {
        return URI.create(baseUrl + "/o/" + signer.sign(linkId, tokenVersion));
    }
}
