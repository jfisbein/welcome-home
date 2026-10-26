package welcomehome.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Within a major version, migrations only expand the schema, so that the previous release runs against it:
 * production follows the major version's tag, upgrades without a backup, and rolls back by pinning the
 * previous release. A changeset that does not run cannot break the database either, as each runs in a
 * transaction and Postgres rolls DDL back.
 */
class MigrationCompatibilityTests {

    /**
     * The first changeset that must keep the previous release working. Those before it predate the rule; a new
     * major version moves it past the changesets that break, and production to that major's tag.
     */
    static final int COMPATIBLE_FROM = 20;

    /** What the previous release relies on: tables and columns as they are, writes it makes, grants it uses. */
    static final Set<String> BREAKING_ELEMENTS = Set.of("dropTable", "dropColumn", "renameTable", "renameColumn",
            "modifyDataType", "addNotNullConstraint", "addUniqueConstraint", "dropView", "renameView", "sqlFile");

    /** The same, written as SQL. */
    static final Pattern BREAKING_SQL = Pattern.compile(
            "\\b(DROP|RENAME|TRUNCATE|REVOKE)\\b|ALTER\\s+COLUMN\\s+\\S+\\s+(TYPE|SET\\s+NOT\\s+NULL)",
            Pattern.CASE_INSENSITIVE);

    static final Set<String> DEFAULT_ATTRIBUTES = Set.of("defaultValue", "defaultValueComputed",
            "defaultValueNumeric", "defaultValueBoolean", "defaultValueDate");

    @Test
    void changesetsWithinTheMajorVersionOnlyExpandTheSchema() throws Exception {
        var problems = new ArrayList<String>();
        var checked = 0;
        for (var file : changelogs()) {
            var name = file.getFilename();
            if (name == null || number(name) < COMPATIBLE_FROM) {
                continue;
            }
            checked++;
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.getInputStream());
            for (var changeSet : elements(document.getDocumentElement(), "changeSet")) {
                check(name, changeSet, problems);
            }
        }

        assertThat(problems).as("checked %d changelogs from %03d on", checked, COMPATIBLE_FROM).isEmpty();
    }

    @Test
    void theCheckFindsWhatBreaksThePreviousRelease() throws Exception {
        var problems = new ArrayList<String>();
        var changeSet = parse("""
                <changeSet id="x" author="test" runInTransaction="false">
                    <dropColumn tableName="owner" columnName="email"/>
                    <addColumn tableName="lock">
                        <column name="required" type="text"><constraints nullable="false"/></column>
                        <column name="defaulted" type="text" defaultValue="a"><constraints nullable="false"/></column>
                        <column name="optional" type="text"/>
                    </addColumn>
                    <sql>ALTER TABLE lock ALTER COLUMN name TYPE varchar(10);</sql>
                    <sql>GRANT SELECT ON lock TO app_user;</sql>
                </changeSet>
                """);

        check("999-test.xml", changeSet, problems);

        assertThat(problems).containsExactly(
                "999-test.xml x: runs outside a transaction",
                "999-test.xml x: <dropColumn>",
                "999-test.xml x: column required, NOT NULL with no default",
                "999-test.xml x: SQL ALTER TABLE lock ALTER COLUMN name TYPE varchar(10);");
    }

    private static void check(String file, Element changeSet, List<String> problems) {
        var where = file + " " + changeSet.getAttribute("id") + ": ";
        if ("false".equals(changeSet.getAttribute("runInTransaction"))) {
            problems.add(where + "runs outside a transaction");
        }
        for (var element : elements(changeSet, "*")) {
            var tag = element.getLocalName() != null ? element.getLocalName() : element.getTagName();
            if (BREAKING_ELEMENTS.contains(tag)) {
                problems.add(where + "<" + tag + ">");
            }
            if (tag.equals("sql") && BREAKING_SQL.matcher(element.getTextContent()).find()) {
                problems.add(where + "SQL " + element.getTextContent().strip());
            }
            if (tag.equals("column") && isAdded(element) && isNotNull(element) && !hasDefault(element)) {
                problems.add(where + "column " + element.getAttribute("name") + ", NOT NULL with no default");
            }
        }
    }

    private static boolean isAdded(Element column) {
        return column.getParentNode() instanceof Element parent && parent.getTagName().equals("addColumn");
    }

    private static boolean isNotNull(Element column) {
        return elements(column, "constraints").stream()
                .anyMatch(constraints -> "false".equals(constraints.getAttribute("nullable")));
    }

    private static boolean hasDefault(Element column) {
        return DEFAULT_ATTRIBUTES.stream().anyMatch(column::hasAttribute);
    }

    private static List<Element> elements(Element parent, String tag) {
        var found = new ArrayList<Element>();
        var nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                found.add((Element) nodes.item(i));
            }
        }
        return found;
    }

    private static int number(String file) {
        return Integer.parseInt(file.substring(0, file.indexOf('-')));
    }

    private static Resource[] changelogs() throws IOException {
        var files = new PathMatchingResourcePatternResolver().getResources("classpath:db/changelog/changes/*.xml");
        assertThat(files).as("changelogs found").hasSizeGreaterThan(COMPATIBLE_FROM - 2);
        return files;
    }

    private static Element parse(String xml) throws ParserConfigurationException, IOException, SAXException {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
    }
}
