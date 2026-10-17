package welcomehome.crypto;

import java.security.GeneralSecurityException;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.Mac;
import com.google.crypto.tink.RegistryConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Loads both keysets at startup: without them the application does not start.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CryptoProperties.class)
class CryptoConfiguration {

    @Bean
    Aead aead(CryptoProperties properties) throws GeneralSecurityException {
        return Keysets.read(properties.aeadKeysetFile(), "AEAD_KEYSET_FILE")
                .getPrimitive(RegistryConfiguration.get(), Aead.class);
    }

    @Bean
    Mac mac(CryptoProperties properties) throws GeneralSecurityException {
        return Keysets.read(properties.macKeysetFile(), "MAC_KEYSET_FILE")
                .getPrimitive(RegistryConfiguration.get(), Mac.class);
    }
}
