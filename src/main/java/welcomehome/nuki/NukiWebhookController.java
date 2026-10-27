package welcomehome.nuki;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives Nuki's webhooks at welcome-home's central webhook URL, {@code /nuki/webhook} under the public URL, which
 * is set in Nuki Web (API, Advanced API). Nuki signs every webhook with the OAuth2 client's secret
 * (https://docs.nuki.io/guide/webhooks/monitoring): one without a valid signature is refused with 401 and read no
 * further. Only reports of actions are used, by the id of the request that sent the action; Nuki's test webhook,
 * sent when webhooks are turned on, and the webhooks about devices are answered and ignored, as Nuki turns off the
 * webhooks of a client whose webhooks fail too often. Without an OAuth2 client there is no secret to check
 * signatures with, and no such address.
 */
@RestController
class NukiWebhookController {

    private static final Logger log = LoggerFactory.getLogger(NukiWebhookController.class);

    static final String SIGNATURE_HEADER = "X-Nuki-Signature-SHA256";

    /** Far larger than any of Nuki's webhooks. */
    static final int MAX_BODY_SIZE = 64 * 1024;

    /** Nuki's error codes, which its webhooks carry as hexadecimal text (https://docs.nuki.io/guide/endpoints/error-codes). */
    private static final int MOTOR_BLOCKED = 0x42;
    private static final int REJECTED_NIGHT_MODE = 0x4F;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NukiProperties properties;
    private final ActionReports reports;

    NukiWebhookController(NukiProperties properties, ActionReports reports) {
        this.properties = properties;
        this.reports = reports;
    }

    @PostMapping("/nuki/webhook")
    ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        var oauth = properties.oauth();
        if (!oauth.enabled()) {
            return ResponseEntity.notFound().build();
        }
        // The signature is of the body exactly as sent, so it is read as bytes, before any parsing.
        byte[] body;
        try (var input = request.getInputStream()) {
            body = input.readNBytes(MAX_BODY_SIZE + 1);
        }
        if (body.length > MAX_BODY_SIZE) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        if (!signedWith(oauth.clientSecret(), body, request.getHeader(SIGNATURE_HEADER))) {
            log.warn("Refused a webhook that Nuki did not sign: a wrong client secret, or not Nuki");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        handle(body);
        return ResponseEntity.noContent().build();
    }

    private void handle(byte[] body) {
        JsonNode webhook;
        try {
            webhook = JSON.readTree(body);
        } catch (JacksonException e) {
            log.warn("Nuki sent a webhook that is not JSON");
            return;
        }
        var requestId = webhook.path("requestId");
        var success = webhook.path("success");
        if (requestId.isString() && !requestId.stringValue().isBlank() && success.isBoolean()) {
            var errorCode = errorCode(webhook);
            log.info("Nuki reported request {} ({}) on smartlock {}: {}{}", requestId.stringValue(),
                    webhook.path("type").asString("no type"), webhook.path("smartlockId").asString("unknown"),
                    success.booleanValue() ? "succeeded" : "failed",
                    errorCode.isMissingNode() ? "" : ", error code " + errorCode.asString());
            reports.report(requestId.stringValue(), outcome(success.booleanValue(), errorCode));
        } else if (webhook.has("feature")) {
            log.debug("Ignored Nuki's {} webhook", webhook.path("feature").asString());
        } else {
            log.info("Nuki sent a webhook that reports no action, such as its test webhook");
        }
    }

    /** At the top, or in the details of the first device the request was about, as in Nuki's examples. */
    private static JsonNode errorCode(JsonNode webhook) {
        var errorCode = webhook.path("errorCode");
        return errorCode.isMissingNode() ? webhook.path("detail").path(0).path("errorCode") : errorCode;
    }

    static LockActionState outcome(boolean success, JsonNode errorCode) {
        if (success) {
            return LockActionState.SUCCEEDED;
        }
        var code = errorCode.isIntegralNumber() ? Integer.valueOf(errorCode.intValue()) : hex(errorCode.asString(""));
        if (code == null) {
            return LockActionState.FAILED;
        }
        return switch (code) {
            case MOTOR_BLOCKED -> LockActionState.MOTOR_BLOCKED;
            case REJECTED_NIGHT_MODE -> LockActionState.REJECTED_NIGHT_MODE;
            default -> LockActionState.FAILED;
        };
    }

    /** Such as "4f", "4F" or "0x4F". */
    private static @Nullable Integer hex(String text) {
        var digits = text.strip().toLowerCase(Locale.ROOT);
        digits = digits.startsWith("0x") ? digits.substring(2) : digits;
        try {
            return digits.isEmpty() ? null : Integer.parseInt(digits, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Tells whether the signature is the body's HMAC-SHA256 with the secret, in hexadecimal, as Nuki signs its
     * webhooks; compared in constant time, so that its timing tells nothing about how close a forgery came.
     */
    static boolean signedWith(String secret, byte[] body, @Nullable String signature) {
        if (signature == null) {
            return false;
        }
        byte[] expected;
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            expected = HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Every Java platform has HmacSHA256", e);
        }
        var received = signature.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, received);
    }
}
