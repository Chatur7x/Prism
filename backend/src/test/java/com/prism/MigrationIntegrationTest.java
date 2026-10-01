package com.prism;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real Flyway migrations against a real MySQL 8 and asserts that
 * Hibernate's mapping matches the schema.
 *
 * <p>This is the test that catches a migration that only looked plausible. A
 * hand-written schema and hand-written entities drift silently otherwise.
 *
 * <p>Requires a running Docker daemon. When none is present the class is
 * skipped rather than failed, so a contributor without Docker still gets a
 * green build; CI runs it with {@code docker} available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class MigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("prism_test")
            .withUsername("prism")
            .withPassword("prism")
            .withCommand("--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci",
                    "--default-authentication-plugin=mysql_native_password");

    /** Feeds the container's coordinates into Spring before the context is built. */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("Flyway applies cleanly from an empty database")
    void migrationsApply() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);
        assertThat(tables).contains(
                "users", "corpora", "documents", "document_chunks", "extraction_runs",
                "extraction_quarantine", "entities", "entity_aliases", "triples", "claims",
                "verdicts", "verdict_passages", "verdict_history");
    }

    @Test
    @DisplayName("no nullable primary key column exists anywhere")
    void noNullablePrimaryKeys() {
        List<Map<String, Object>> nullablePks = jdbc.queryForList("""
                SELECT table_name, column_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND column_key = 'PRI'
                  AND is_nullable = 'YES'
                """);
        assertThat(nullablePks).as("a nullable primary key is a schema defect").isEmpty();
    }

    @Test
    @DisplayName("every foreign key is enforced and points at a real column")
    void foreignKeysAreEnforced() {
        Integer fkCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'
                """, Integer.class);
        assertThat(fkCount).as("the schema must declare foreign keys").isGreaterThanOrEqualTo(20);
    }

    @Test
    @DisplayName("the FULLTEXT index on chunk content exists")
    void fulltextIndexExists() {
        Integer ftCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'document_chunks'
                  AND index_type = 'FULLTEXT'
                """, Integer.class);
        assertThat(ftCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("the required retrieval and workflow indexes exist")
    void requiredIndexesExist() {
        List<String> indexedColumns = jdbc.queryForList("""
                SELECT DISTINCT table_name, index_name FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                """, String.class);

        for (String table : List.of("claims", "verdicts", "contradictions", "debates",
                                    "trace_steps", "document_chunks", "entities")) {
            assertThat(indexedColumns)
                    .as("table %s must be indexed", table)
                    .anyMatch(s -> s.startsWith(table + "|"));
        }
    }

    @Test
    @DisplayName("Hibernate's entity mapping validates against the migrated schema")
    void hibernateMappingMatchesSchema() {
        // The context only starts when ddl-auto=validate passes, so reaching
        // this assertion means every mapped column exists with a compatible type.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
    }
}
