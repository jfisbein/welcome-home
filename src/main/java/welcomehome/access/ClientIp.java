package welcomehome.access;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The guest's IP address, for the rate limits and the history. Behind Cloudflare it comes from
 * CF-Connecting-IP, which Cloudflare sets on every request, overwriting what the client sent; X-Forwarded-For,
 * by contrast, starts with whatever the client sent. Without that header, or when it holds no IP address, it
 * is the address the request came from.
 */
@Component
class ClientIp {

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*");

    private final @Nullable String header;

    ClientIp(AccessProperties properties) {
        var configured = properties.clientIpHeader();
        this.header = configured == null || configured.isBlank() ? null : configured.strip();
    }

    String of(HttpServletRequest request) {
        var value = header == null ? null : request.getHeader(header);
        return Optional.ofNullable(value)
                .flatMap(ip -> ipLiteral(ip.strip()))
                .orElseGet(request::getRemoteAddr);
    }

    /** Only IP literals: InetAddress would look a host name up in DNS, but parses an IPv6 literal. */
    private static Optional<String> ipLiteral(String value) {
        if (IPV4.matcher(value).matches()) {
            return Optional.of(value);
        }
        if (IPV6.matcher(value).matches()) {
            try {
                return Optional.of(InetAddress.getByName(value).getHostAddress());
            } catch (UnknownHostException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
