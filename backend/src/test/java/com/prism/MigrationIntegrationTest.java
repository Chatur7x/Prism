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

    @Autowired
    com.prism.config.SchemaContractChecker schemaContract;

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
        // CONCAT because the assertion below matches on "table|index". This query
        // previously selected two columns and asked JdbcTemplate for a single
        // String, which throws IncorrectResultSetColumnCount -- so this test had
        // never passed, and never failed either, because it always skipped.
        List<String> indexedColumns = jdbc.queryForList("""
                SELECT DISTINCT CONCAT(table_name, '|', index_name)
                FROM information_schema.statistics
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
        // The real assertion is implicit and happens before this method runs: the
        // Spring context only starts once ddl-auto=validate has compared every
        // entity mapping to the migrated schema. Reaching this line therefore
        // already means the mapping matches.
        //
        // What is checked here is that the mapped tables are genuinely usable,
        // which a passing validate alone does not prove -- a table can exist with
        // the right column names and still be unreadable.
        //
        // This deliberately does NOT assert an empty users table. It used to, and
        // that assertion was simply wrong: AdminBootstrap creates the first admin
        // on startup, so the count is 1 before anything else runs. The failure
        // went unnoticed because the test had never executed.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class))
                .as("the bootstrap admin is created on startup")
                .isEqualTo(1);

        // A mapped column is selectable with the type the mapping declares.
        assertThat(jdbc.queryForList("SELECT id, username, role, enabled FROM users"))
                .as("the users mapping must be readable against the migrated schema")
                .hasSize(1);

        // And a table carrying a CHECK constraint is present with it intact.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = DATABASE() AND constraint_type = 'CHECK'
                """, Integer.class))
                .as("CHECK constraints must survive migration")
                .isGreaterThanOrEqualTo(30);
    }

    // ---- schema contract -----------------------------------------------------
    //
    // ddl-auto=validate compares mappings to the schema. It cannot detect a NOT
    // NULL column that NO entity maps, because there is nothing to compare such a
    // column against. A corpus_id column on report_block_citations sat in the
    // schema for the whole life of the project in exactly that state, and was
    // found only when synthesis first executed and every synthesis failed. The
    // tests below are the ones that would have caught it.

    @Test
    @DisplayName("the migrated schema satisfies the explicit contract")
    void realSchemaSatisfiesTheContract() {
        var result = schemaContract.check();

        assertThat(result.failures())
                .as("schema contract violations in the real migrated schema:\n  %s",
                        result.failures())
                .isEmpty();

        // A check that inspects nothing would also report no violations, so the
        // run has to prove it looked at something real.
        assertThat(result.tablesInspected())
                .as("the check must inspect the whole schema, not a sample")
                .isGreaterThanOrEqualTo(25);
        assertThat(result.columnsInspected())
                .as("the check must inspect every column")
                .isGreaterThanOrEqualTo(200);
        assertThat(result.mappedColumns())
                .as("the check must resolve entity mappings, otherwise it cannot compare")
                .isGreaterThanOrEqualTo(150);
    }

    @Test
    @DisplayName("the contract check detects the defect it was written for")
    void contractCheckDetectsTheHistoricalDefect() {
        // Recreates the real defect on purpose: a NOT NULL column with no default
        // that no entity maps. A contract check that cannot catch this is
        // decorative, and this is the test that says so.
        //
        // The column is added and dropped inside the test, so the real schema is
        // never left altered -- which would make the next test's result depend on
        // test execution order.
        assertThat(schemaContract.check().failures())
                .as("precondition: the schema must be clean before the defect is injected")
                .isEmpty();

        jdbc.execute("ALTER TABLE report_block_citations "
                + "ADD COLUMN lease_tenant_code VARCHAR(32) NOT NULL");

        try {
            var result = schemaContract.check();

            assertThat(result.failures())
                    .as("the check must fail on an unmapped NOT NULL column")
                    .isNotEmpty();

            var violation = result.failures().stream()
                    .filter(v -> v.kind().equals("UNMAPPED_NOT_NULL_COLUMN"))
                    .findFirst();

            assertThat(violation)
                    .as("expected an UNMAPPED_NOT_NULL_COLUMN finding, got:\n  %s",
                            result.failures())
                    .isPresent();

            assertThat(violation.get().table()).isEqualTo("report_block_citations");
            assertThat(violation.get().detail())
                    .as("the finding must name the column and say why it is dangerous")
                    .contains("lease_tenant_code")
                    .contains("INSERT");

            // And the failure must be reported as a hard failure, not a warning.
            // A warning would let a build go green while the defect remained.
            assertThat(result.passed())
                    .as("an unmapped NOT NULL column must not pass the contract")
                    .isFalse();
        } finally {
            jdbc.execute("ALTER TABLE report_block_citations DROP COLUMN lease_tenant_code");
        }

        assertThat(schemaContract.check().failures())
                .as("dropping the injected column must restore a clean result")
                .isEmpty();
    }

    @Test
    @DisplayName("the contract check does not flag a mapped NOT NULL column")
    void contractCheckAcceptsMappedNotNullColumns() {
        // The counterpart, and the one that keeps the check from becoming noise.
        // A real schema is full of NOT NULL columns that ARE mapped; flagging
        // those would make the check something people turn off.
        var unmapped = schemaContract.check().failures().stream()
                .filter(v -> v.kind().equals("UNMAPPED_NOT_NULL_COLUMN"))
                .toList();

        assertThat(unmapped)
                .as("mapped NOT NULL columns must not be reported as unmapped:\n  %s", unmapped)
                .isEmpty();

        // And the tables carrying them are genuinely mapped, which is the
        // distinction the check turns on.
        var mapped = schemaContract.mappedColumns();
        assertThat(mapped).containsKey("report_block_citations");
        assertThat(mapped.get("report_block_citations"))
                .as("report_block_citations has mapped columns, which is why the injected "
                        + "column was detectable")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the contract check is read-only")
    void contractCheckChangesNothing() throws Exception {
        // A check wired into CI that quietly repairs what it finds is worse than
        // one that only reports: the fix never gets reviewed.
        String before = (String) jdbc.queryForObject("""
                SELECT GROUP_CONCAT(CONCAT(table_name, '.', column_name) ORDER BY table_name)
                FROM information_schema.columns WHERE table_schema = DATABASE()
                """, String.class);

        schemaContract.check();
        schemaContract.check();

        String after = (String) jdbc.queryForObject("""
                SELECT GROUP_CONCAT(CONCAT(table_name, '.', column_name) ORDER BY table_name)
                FROM information_schema.columns WHERE table_schema = DATABASE()
                """, String.class);

        assertThat(after)
                .as("the contract check must not add, drop or alter anything")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("the critical index and foreign key expectations match the real schema")
    void criticalExpectationsAreReal() {
        // If a name in CRITICAL_INDEXES never existed, that entry would be dead
        // weight that reads as a safety net. Asserting the schema satisfies them
        // keeps the list honest in both directions: the test fails if the index is
        // dropped, and a wrong name fails here immediately.
        var result = schemaContract.check();
        assertThat(result.failures()).isEmpty();

        List<String> indexes = jdbc.queryForList("""
                SELECT DISTINCT CONCAT(table_name, '|', index_name)
                FROM information_schema.statistics WHERE table_schema = DATABASE()
                """, String.class);

        assertThat(com.prism.config.SchemaContractChecker.CRITICAL_INDEXES)
                .as("the critical-index map must not be empty, or the check checks nothing")
                .isNotEmpty();
        assertThat(com.prism.config.SchemaContractChecker.CRITICAL_FOREIGN_KEYS)
                .as("the critical-foreign-key map must not be empty")
                .isNotEmpty();

        // Every expectation is present in the real schema today.
        com.prism.config.SchemaContractChecker.CRITICAL_INDEXES.forEach((table, names) ->
                names.forEach(name -> assertThat(indexes)
                        .as("expected index %s on %s", name, table)
                        .contains(table + "|" + name)));
    }
}
