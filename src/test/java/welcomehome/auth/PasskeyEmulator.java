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
final class PasskeyEmulator {

    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final ClientPlatform browser;
    private final ObjectConverter webauthn4j = new ObjectConverter();
    private final JsonMapper json = JsonMapper.builder().build();

    PasskeyEmulator(String origin) {
        browser = new ClientPlatform(new Origin(origin), new WebAuthnAuthenticatorAdaptor(new NoneAttestationAuthenticator()));
    }

    /** navigator.credentials.create: returns the body posted to /invite/{token}/register. */
    String create(String creationOptionsJson) {
        var options = webauthn4j.getJsonConverter().readValue(creationOptionsJson, PublicKeyCredentialCreationOptions.class);
        var credential = browser.create(options);
        var response = credential.getResponse();
        return json.writeValueAsString(Map.of(
                "id", credential.getId(),
                "rawId", BASE64_URL.encodeToString(credential.getRawId()),
                "type", "public-key",
                "response", Map.of(
                        "attestationObject", BASE64_URL.encodeToString(response.getAttestationObject()),
                        "clientDataJSON", BASE64_URL.encodeToString(response.getClientDataJSON()),
                        "transports", List.of()),
                "clientExtensionResults", Map.of()));
    }

    /** navigator.credentials.get: returns the body posted to /login/webauthn. */
    String get(String requestOptionsJson) {
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
