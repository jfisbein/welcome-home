package welcomehome.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Templates hold no text of their own: every text a person reads comes from the bundles, in their language.
 * Like the route guard of OwnerIsolationTests, it catches the next page written with Spanish in it.
 */
class TemplateTextTests {

    /** Attributes a person reads or hears. */
    static final Set<String> TEXT_ATTRIBUTES = Set.of("placeholder", "title", "aria-label", "alt", "data-confirm");

    /** Thymeleaf puts something else in place of the element's content. */
    static final Set<String> CONTENT_REPLACED = Set.of("th:text", "th:utext");

    /** Thymeleaf puts something else in place of the element itself, attributes included. */
    static final Set<String> ELEMENT_REPLACED = Set.of("th:replace", "th:insert");

    /** Not to be translated. */
    static final Set<String> NAMES = Set.of("welcome-home");

    static final Pattern WORD = Pattern.compile("\\p{L}{2,}");
    static final Pattern INLINED = Pattern.compile("\\[\\[.*?]]", Pattern.DOTALL);

    @Test
    void noTemplateHasTextOfItsOwn() throws IOException {
        var problems = new ArrayList<String>();
        for (var template : templates()) {
            for (var element : template.document().getAllElements()) {
                if (isRemoved(element)) {
                    continue;
                }
                var text = INLINED.matcher(element.ownText()).replaceAll("");
                for (var name : NAMES) {
                    text = text.replace(name, "");
                }
                if (!hasAny(element, CONTENT_REPLACED) && WORD.matcher(text).find()) {
                    problems.add(template.name() + ": <" + element.tagName() + "> " + text.strip());
                }
                for (var attribute : TEXT_ATTRIBUTES) {
                    if (!element.hasAttr("th:" + attribute) && WORD.matcher(element.attr(attribute)).find()) {
                        problems.add(template.name() + ": " + attribute + "=\"" + element.attr(attribute) + "\"");
                    }
                }
            }
        }

        assertThat(problems).isEmpty();
    }

    @Test
    void unescapedMessagesEscapeEveryValueTheyShow() throws IOException {
        var problems = new ArrayList<String>();
        for (var template : templates()) {
            for (var element : template.document().getElementsByAttribute("th:utext")) {
                var expression = element.attr("th:utext");
                var values = Pattern.compile("\\$\\{").matcher(expression);
                while (values.find()) {
                    if (!expression.startsWith("${#strings.escapeXml(", values.start())) {
                        problems.add(template.name() + ": " + expression);
                    }
                }
            }
        }

        assertThat(problems).isEmpty();
    }

    /** Whether the element is only there for the template to read as a page, and never rendered. */
    private static boolean isRemoved(Element element) {
        if (hasAny(element, ELEMENT_REPLACED)) {
            return true;
        }
        for (var ancestor : element.parents()) {
            if (hasAny(ancestor, CONTENT_REPLACED) || hasAny(ancestor, ELEMENT_REPLACED)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAny(Element element, Set<String> attributes) {
        return attributes.stream().anyMatch(element::hasAttr);
    }

    private static List<Template> templates() throws IOException {
        var templates = new ArrayList<Template>();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath:templates/**/*.html")) {
            var path = resource.getURI().toString();
            templates.add(new Template(path.substring(path.indexOf("templates/")),
                    Jsoup.parse(resource.getContentAsString(StandardCharsets.UTF_8))));
        }
        assertThat(templates).as("templates found").hasSizeGreaterThan(10);
        return templates;
    }

    record Template(String name, Document document) {
    }
}
