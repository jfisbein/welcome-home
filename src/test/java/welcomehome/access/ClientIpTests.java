package welcomehome.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import welcomehome.access.AccessProperties.Confirmation;
import welcomehome.access.AccessProperties.RateLimit;

class ClientIpTests {

    static final String PROXY = "172.18.0.5";

    final ClientIp clientIp = clientIp("CF-Connecting-IP");

    @Test
    void ipAddressComesFromTheHeader() {
        assertThat(clientIp.of(request(" 198.51.100.7 "))).isEqualTo("198.51.100.7");
        assertThat(clientIp.of(request("2001:DB8:0:0:0:0:0:1"))).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void withoutTheHeaderItIsTheAddressTheRequestCameFrom() {
        assertThat(clientIp.of(request(null))).isEqualTo(PROXY);
    }

    @Test
    void headerWithoutAnIpAddressIsIgnored() {
        for (var value : new String[] {"", "example.com", "localhost", "198.51.100.7, 203.0.113.1", "256.1.1.1",
                "1.2.3", "fe80::1%eth0", "::g"}) {
            assertThat(clientIp.of(request(value))).as(value).isEqualTo(PROXY);
        }
    }

    @Test
    void blankHeaderSettingUsesTheAddressTheRequestCameFrom() {
        assertThat(clientIp(" ").of(request("198.51.100.7"))).isEqualTo(PROXY);
        assertThat(clientIp(null).of(request("198.51.100.7"))).isEqualTo(PROXY);
    }

    private static MockHttpServletRequest request(@Nullable String header) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(PROXY);
        if (header != null) {
            request.addHeader("CF-Connecting-IP", header);
        }
        return request;
    }

    private static ClientIp clientIp(@Nullable String header) {
        return new ClientIp(new AccessProperties(new RateLimit(10, 5, Duration.ofMinutes(1)),
                new Confirmation(Duration.ofSeconds(15), Duration.ofSeconds(2)), header));
    }
}
