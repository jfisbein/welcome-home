package welcomehome.auth;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import tools.jackson.databind.json.JsonMapper;

/**
 * A browser with a passkey authenticator: it reads the options JSON the app sends and answers with the
 * same JSON that static/js/webauthn.js posts. The authenticator keeps its passkeys, so a passkey created
 * with {@link #create} can sign in with {@link #get}.
 */
public final class PasskeyEmulator {

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final ClientPlatform browser;
    private final ObjectConverter webauthn4j = new ObjectConverter();
    private final JsonMapper json = JsonMapper.builder().build();

    public PasskeyEmulator(String origin) {
        this(origin, true);
    }

    /**
     * A security key that did not verify its user, such as one without a PIN: its registrations say so. The emulated
     * authenticator always verifies, so the flag is cleared afterwards, which "none" attestation, signing nothing,
     * allows. Its sign-ins are not changed, as clearing the flag would break their signature instead.
     */
    static PasskeyEmulator withoutUserVerification(String origin) {
        return new PasskeyEmulator(origin, false);
    }

    /** The user verified bit in an authenticator data's flags, the byte after the 32 bytes of the RP id's hash. */
    private static final int USER_VERIFIED = 0x04;
    private static final int FLAGS = 32;

    private final boolean verifiesUser;

    private PasskeyEmulator(String origin, boolean verifiesUser) {
        this.verifiesUser = verifiesUser;
        browser = new ClientPlatform(new Origin(origin), new WebAuthnAuthenticatorAdaptor(new NoneAttestationAuthenticator()));
    }

    private byte[] asAttested(byte[] attestationObject) {
        if (verifiesUser) {
            return attestationObject;
        }
        var cbor = webauthn4j.getCborConverter();
        @SuppressWarnings("unchecked")
        Map<String, Object> attestation = cbor.readValue(attestationObject, Map.class);
        var authData = ((byte[]) attestation.get("authData")).clone();
        authData[FLAGS] &= (byte) ~USER_VERIFIED;
        var changed = new LinkedHashMap<String, Object>(attestation);
        changed.put("authData", authData);
        return cbor.writeValueAsBytes(changed);
    }

    /** navigator.credentials.create: returns the body posted to /invite/{token}/register. */
    public String create(String creationOptionsJson) {
        var options = webauthn4j.getJsonConverter().readValue(creationOptionsJson, PublicKeyCredentialCreationOptions.class);
        var credential = browser.create(options);
        var response = credential.getResponse();
        return json.writeValueAsString(Map.of(
                "id", credential.getId(),
                "rawId", BASE64_URL.encodeToString(credential.getRawId()),
                "type", "public-key",
                "response", Map.of(
                        "attestationObject", BASE64_URL.encodeToString(asAttested(response.getAttestationObject())),
                        "clientDataJSON", BASE64_URL.encodeToString(response.getClientDataJSON()),
                        "transports", List.of()),
                "clientExtensionResults", Map.of()));
    }

    /** navigator.credentials.get: returns the body posted to /login/webauthn. */
    public String get(String requestOptionsJson) {
        var options = webauthn4j.getJsonConverter().readValue(requestOptionsJson, PublicKeyCredentialRequestOptions.class);
        var credential = browser.get(options);
        var response = credential.getResponse();
        var assertion = new LinkedHashMap<String, Object>();
        assertion.put("authenticatorData", BASE64_URL.encodeToString(response.getAuthenticatorData()));
        assertion.put("clientDataJSON", BASE64_URL.encodeToString(response.getClientDataJSON()));
        assertion.put("signature", BASE64_URL.encodeToString(response.getSignature()));
        assertion.put("userHandle", BASE64_URL.encodeToString(response.getUserHandle()));
        return json.writeValueAsString(Map.of(
                "id", credential.getId(),
                "rawId", BASE64_URL.encodeToString(credential.getRawId()),
                "type", "public-key",
                "response", assertion,
                "clientExtensionResults", Map.of()));
    }
}
