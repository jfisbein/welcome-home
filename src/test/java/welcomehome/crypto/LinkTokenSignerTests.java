package welcomehome.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinkTokenSignerTests {

    static final UUID LINK_ID = UUID.fromString("0b6f1c9e-3f0a-4c55-9a3e-6f2d8c1b7a40");

    static LinkTokenSigner signer;

    @BeforeAll
    static void createSigner() throws Exception {
        signer = new LinkTokenSigner(TestPrimitives.mac());
    }

    @Test
    void signedTokenIsTheLinkIdAndABase64UrlMac() {
        var token = signer.sign(LINK_ID, 1);

        assertThat(token).matches(LINK_ID + "\\.[A-Za-z0-9_-]{28}");
        assertThat(signer.parseLinkId(token)).contains(LINK_ID);
        assertThat(signer.verify(token, 1)).isTrue();
    }

    @Test
    void signingIsDeterministic() {
        assertThat(signer.sign(LINK_ID, 1)).isEqualTo(signer.sign(LINK_ID, 1));
    }

    @Test
    void alteredMacIsRejected() {
        var token = signer.sign(LINK_ID, 1);
        var macStart = token.indexOf('.') + 1;
        var altered = token.substring(0, macStart)
                + (token.charAt(macStart) == 'A' ? 'B' : 'A')
                + token.substring(macStart + 1);

        assertThat(signer.verify(altered, 1)).isFalse();
    }

    @Test
    void tokenOfAPreviousVersionIsRejected() {
        var previous = signer.sign(LINK_ID, 1);

        assertThat(signer.verify(previous, 2)).isFalse();
    }

    @Test
    void macOfAnotherLinkIsRejected() {
        var otherLinksMac = signer.sign(UUID.randomUUID(), 1).split("\\.")[1];

        assertThat(signer.verify(LINK_ID + "." + otherLinksMac, 1)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "no-dot",
            "0b6f1c9e-3f0a-4c55-9a3e-6f2d8c1b7a40.",
            "0b6f1c9e-3f0a-4c55-9a3e-6f2d8c1b7a40.not+base64url",
            "0b6f1c9e-3f0a-4c55-9a3e-6f2d8c1b7a40.AAAA.AAAA",
            "not-a-uuid.AAAA",
            "0B6F1C9E-3F0A-4C55-9A3E-6F2D8C1B7A40.AAAA",
            "b6f1c9e-3f0a-4c55-9a3e-6f2d8c1b7a40.AAAA"
    })
    void malformedTokensAreRejected(String token) {
        assertThat(signer.verify(token, 1)).isFalse();
    }

    @Test
    void nonCanonicalLinkIdIsNotParsed() {
        assertThat(signer.parseLinkId("0B6F1C9E-3F0A-4C55-9A3E-6F2D8C1B7A40.AAAA")).isEmpty();
        assertThat(signer.parseLinkId("1-1-1-1-1.AAAA")).isEmpty();
    }

    @Test
    void overlongTokenIsRejected() {
        var token = signer.sign(LINK_ID, 1) + "A".repeat(100);

        assertThat(signer.parseLinkId(token)).isEmpty();
        assertThat(signer.verify(token, 1)).isFalse();
    }
}
