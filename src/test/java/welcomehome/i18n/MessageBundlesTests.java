package welcomehome.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import welcomehome.config.Languages;

/** The bundles agree with each other and with what the templates ask for. */
class MessageBundlesTests {

    /** A literal key: #{key} or #{key(arguments)}, and #messages.msg('key'), but not a key built at run time. */
    static final Pattern TEMPLATE_KEY = Pattern.compile("#\\{([\\w.-]+)|#messages\\.msg\\('([\\w.-]+)'\\)");

    @Test
    void everyLanguageHasABundleWithExactlyTheDefaultBundlesKeys() throws IOException {
        var defaultKeys = load("i18n/messages.properties").stringPropertyNames();

        for (var language : Languages.SUPPORTED) {
            if (!language.equals(Languages.DEFAULT)) {
                assertThat(load(bundle(language)).stringPropertyNames()).as(language.toString())
                        .containsExactlyInAnyOrderElementsOf(defaultKeys);
            }
        }
    }

    @Test
    void everyKeyATemplateUsesIsInTheDefaultBundle() throws IOException {
        var keys = load("i18n/messages.properties").stringPropertyNames();
        var used = new TreeSet<String>();
        for (var template : new PathMatchingResourcePatternResolver().getResources("classpath:templates/**/*.html")) {
            var matcher = TEMPLATE_KEY.matcher(template.getContentAsString(StandardCharsets.UTF_8));
            while (matcher.find()) {
                used.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            }
        }

        assertThat(keys).containsAll(used);
    }

    private static String bundle(Locale language) {
        return "i18n/messages_" + language.getLanguage() + ".properties";
    }

    private static Properties load(String path) throws IOException {
        Resource resource = new ClassPathResource(path);
        assertThat(resource.exists()).as(path).isTrue();
        var properties = new Properties();
        try (var reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
