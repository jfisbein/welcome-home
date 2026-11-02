package welcomehome.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Where to look for a query: in a DAO. Beyond them, only what binds a transaction to its owner runs SQL. */
class DataAccessTests {

    @Test
    void onlyDaosRunSql() throws IOException {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            List<Path> others = files
                    .filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.getFileName().toString().endsWith("Dao.java"))
                    .filter(file -> !file.getFileName().toString().equals("OwnerTransactions.java"))
                    .filter(DataAccessTests::usesJdbcClient)
                    .toList();

            assertThat(others).as("classes with SQL that are not DAOs").isEmpty();
        }
    }

    private static boolean usesJdbcClient(Path file) {
        try {
            return Files.readString(file).contains("JdbcClient");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
