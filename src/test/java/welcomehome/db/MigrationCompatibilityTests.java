package welcomehome.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.Map;
import java.util.Arrays;
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

    /**
     * What a changeset may do without anyone looking: only what cannot touch what is there. Anything else is found,
     * whatever it is, rather than only what someone thought of forbidding, and must be in {@link #REVIEWED}.
     */
    static final Pattern HARMLESS_SQL = Pattern.compile("^(GRANT\\b|CREATE\\s+INDEX\\b).*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** SQL that is harmless on a table the same changeset creates, which nothing uses yet: the table is group 2. */
    static final Pattern ON_ITS_OWN_TABLE = Pattern.compile(
            "^(ALTER\\s+TABLE\\s+(\\w+)\\s+(?:ADD\\s+CONSTRAINT|ENABLE\\s+ROW\\s+LEVEL\\s+SECURITY)\\b"
                    + "|CREATE\\s+POLICY\\s+\\w+\\s+ON\\s+(\\w+)\\b|CREATE\\s+UNIQUE\\s+INDEX\\s+\\w+\\s+ON\\s+(\\w+)\\b).*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    static final Set<String> DEFAULT_ATTRIBUTES = Set.of("defaultValue", "defaultValueComputed",
            "defaultValueNumeric", "defaultValueBoolean", "defaultValueDate");

    /**
     * What the check finds in the changesets there are, each looked at by a person, with why the previous release
     * still runs against it and real rows do not stop it. A new finding fails the test until it is added here, with
     * its why; MigrationUpgradeTests runs them all over a database with rows.
     */
    static final Map<String, String> REVIEWED = Map.ofEntries(
            Map.entry("020-lock-device-type-and-entrance.xml: constraint on lock: lock_device_type_check",
                    "on device_type, added with it and its default, which every row and every earlier insert gets"),
            Map.entry("020-lock-device-type-and-entrance.xml: constraint on lock: lock_entrance_not_itself_check",
                    "on entrance_lock_id, added with it and NULL in every row; an earlier release never sets it"),
            Map.entry("020-lock-device-type-and-entrance.xml: constraint on lock: lock_entrance_fkey",
                    "on entrance_lock_id, added with it and NULL in every row; an earlier release never sets it"),
            Map.entry("020-lock-device-type-and-entrance.xml: constraint on access_event: access_event_entrance_fkey",
                    "on entrance_lock_id, added with it and NULL in every row; an earlier release never sets it"),
            Map.entry("022-passkeys-owner.xml: <update> of user_entities",
                    "fills owner_id, added with it, from the email: no row can fail, one without an owner stays NULL"),
            Map.entry("022-passkeys-owner.xml: <addForeignKeyConstraint> on user_entities",
                    "on owner_id, just filled from owner itself, so every value it holds is an owner's"),
            Map.entry("022-passkeys-owner.xml: <delete> from user_credentials",
                    "only passkeys of no user, which cannot sign in, and which the foreign key after it would refuse"),
            Map.entry("022-passkeys-owner.xml: <addForeignKeyConstraint> on user_credentials",
                    "after the delete above, every passkey left has its user; Spring Security inserts the user first"),
            Map.entry("022-passkeys-owner.xml: SQL CREATE FUNCTION set_passkey_owner() RETURNS trigger LANGUAGE",
                    "a new function, used only by the trigger below"),
            Map.entry("022-passkeys-owner.xml: SQL CREATE TRIGGER user_entities_set_owner BEFORE INSERT OR UPDA",
                    "fills owner_id for a release that does not know it, and never fails: no owner gives NULL"),
            Map.entry("029-lock-coordinates.xml: constraint on lock: lock_coordinates_check",
                    "on latitude and longitude, added with it and NULL in every row; an earlier release sets neither"),
            Map.entry("030-location-check.xml: constraint on lock: lock_location_check_check",
                    "on location_check, added with it and its default, which every row and earlier insert gets"),
            Map.entry("030-location-check.xml: constraint on lock: lock_location_radius_check",
                    "on location_radius_m, added with it and its default, which every row and earlier insert gets"),
            Map.entry("035-owner-make-admin.xml: SQL CREATE FUNCTION make_admin(requested_owner_id uuid) RETURNS ",
                    "a new function, which an earlier release never calls and which touches no row until called"),
            Map.entry("035-owner-make-admin.xml: SQL REVOKE ALL ON FUNCTION make_admin(uuid) FROM PUBLIC",
                    "on the function the changeset has just created: nobody could run it yet"));

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

        assertThat(problems).as("checked %d changelogs from %03d on: what is found and nobody has reviewed", checked,
                COMPATIBLE_FROM).filteredOn(problem -> !REVIEWED.containsKey(problem)).isEmpty();
        assertThat(REVIEWED.keySet()).as("reviewed, and no longer found: take it off the list").isSubsetOf(problems);
    }

    @Test
    void theCheckFindsWhateverMayTouchWhatIsThere() throws Exception {
        var problems = new ArrayList<String>();
        var changeSet = parse("""
                <changeSet id="x" author="test" runInTransaction="false">
                    <createTable tableName="thing"><column name="id" type="uuid"/></createTable>
                    <addUniqueConstraint tableName="thing" columnNames="id"/>
                    <createIndex indexName="thing_idx" tableName="lock"><column name="name"/></createIndex>
                    <dropColumn tableName="owner" columnName="email"/>
                    <addColumn tableName="lock">
                        <column name="required" type="text"><constraints nullable="false"/></column>
                        <column name="defaulted" type="text" defaultValue="a"><constraints nullable="false"/></column>
                        <column name="optional" type="text"/>
                        <column name="only" type="text"><constraints unique="true"/></column>
                    </addColumn>
                    <update tableName="lock"><column name="name" value="x"/></update>
                    <delete tableName="lock"/>
                    <addForeignKeyConstraint baseTableName="lock" baseColumnNames="a" constraintName="c"
                                             referencedTableName="owner" referencedColumnNames="id"/>
                    <createIndex indexName="lock_unique" tableName="lock" unique="true"><column name="name"/></createIndex>
                    <sql>ALTER TABLE lock ALTER COLUMN name TYPE varchar(10);</sql>
                    <sql>
                        GRANT SELECT ON lock TO app_user;
                        ALTER TABLE thing ENABLE ROW LEVEL SECURITY;
                        ALTER TABLE thing ADD CONSTRAINT thing_check CHECK (id IS NOT NULL);
                        ALTER TABLE lock ADD CONSTRAINT lock_check CHECK (name IS NOT NULL);
                        CREATE UNIQUE INDEX lock_name ON lock (name);
                        DELETE FROM lock;
                    </sql>
                </changeSet>
                """);

        check("999-test.xml", changeSet, problems);

        assertThat(problems).containsExactly(
                "999-test.xml: runs outside a transaction",
                "999-test.xml: <dropColumn>",
                "999-test.xml: column required, NOT NULL with no default",
                "999-test.xml: column only, with a constraint",
                "999-test.xml: <update> of lock",
                "999-test.xml: <delete> from lock",
                "999-test.xml: <addForeignKeyConstraint> on lock",
                "999-test.xml: unique index on lock",
                "999-test.xml: SQL ALTER TABLE lock ALTER COLUMN name TYPE varchar(10)",
                "999-test.xml: constraint on lock: lock_check",
                "999-test.xml: SQL CREATE UNIQUE INDEX lock_name ON lock (name)",
                "999-test.xml: SQL DELETE FROM lock");
    }

    private static void check(String file, Element changeSet, List<String> problems) {
        var where = file + ": ";
        if ("false".equals(changeSet.getAttribute("runInTransaction"))) {
            problems.add(where + "runs outside a transaction");
        }
        // A table that this changeset creates: nothing uses it yet, so anything may be done to it.
        var created = elements(changeSet, "createTable").stream().map(table -> table.getAttribute("tableName"))
                .collect(Collectors.toSet());
        for (var node = changeSet.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) {
                continue;
            }
            var tag = element.getLocalName() != null ? element.getLocalName() : element.getTagName();
            var table = element.hasAttribute("baseTableName") ? element.getAttribute("baseTableName")
                    : element.getAttribute("tableName");
            switch (tag) {
                case "comment", "createTable" -> { }
                case "addColumn" -> {
                    for (var column : elements(element, "column")) {
                        if (isNotNull(column) && !hasDefault(column)) {
                            problems.add(where + "column " + column.getAttribute("name") + ", NOT NULL with no default");
                        } else if (hasAnotherConstraint(column)) {
                            problems.add(where + "column " + column.getAttribute("name") + ", with a constraint");
                        }
                    }
                }
                case "createIndex" -> {
                    if ("true".equals(element.getAttribute("unique")) && !created.contains(table)) {
                        problems.add(where + "unique index on " + table);
                    }
                }
                case "addPrimaryKey", "addForeignKeyConstraint", "addUniqueConstraint" -> {
                    if (!created.contains(table)) {
                        problems.add(where + "<" + tag + "> on " + table);
                    }
                }
                case "update" -> problems.add(where + "<update> of " + table);
                case "delete" -> problems.add(where + "<delete> from " + table);
                case "sql" -> statements(element.getTextContent()).forEach(sql -> checkSql(where, sql, created, problems));
                default -> problems.add(where + "<" + tag + ">");
            }
        }
    }

    private static void checkSql(String where, String sql, Set<String> created, List<String> problems) {
        if (HARMLESS_SQL.matcher(sql).matches()) {
            return;
        }
        var onATable = ON_ITS_OWN_TABLE.matcher(sql);
        if (onATable.matches()) {
            var table = Stream.of(onATable.group(2), onATable.group(3), onATable.group(4)).filter(Objects::nonNull)
                    .findFirst().orElseThrow();
            if (created.contains(table)) {
                return;
            }
            var constraint = Pattern.compile("ADD\\s+CONSTRAINT\\s+(\\w+)", Pattern.CASE_INSENSITIVE).matcher(sql);
            if (constraint.find()) {
                problems.add(where + "constraint on " + table + ": " + constraint.group(1));
                return;
            }
        }
        problems.add(where + "SQL " + (sql.length() > 60 ? sql.substring(0, 60) : sql));
    }

    /** A changeset's SQL, statement by statement, without its comments: a function's body stays whole. */
    private static List<String> statements(String sql) {
        var withoutComments = sql.lines().map(String::strip).filter(line -> !line.startsWith("--"))
                .collect(Collectors.joining(" ")).strip();
        if (withoutComments.contains("$$")) {
            return List.of(withoutComments);
        }
        return Arrays.stream(withoutComments.split(";")).map(String::strip).filter(statement -> !statement.isEmpty())
                .map(statement -> statement.replaceAll("\\s+", " ")).toList();
    }

    private static boolean hasAnotherConstraint(Element column) {
        return elements(column, "constraints").stream().anyMatch(constraints -> Stream.of("unique", "primaryKey",
                "foreignKeyName", "references", "checkConstraint").anyMatch(constraints::hasAttribute));
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
