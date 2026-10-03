package com.prism.config;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EmbeddableType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Detects schema drift that {@code ddl-auto: validate} structurally cannot.
 *
 * <p>Hibernate's validator compares each mapped entity to the table it maps, so it
 * only ever asks "is what I expect present". It cannot ask the question that
 * actually bit this project: <b>is there a column the application does not know
 * about?</b> There is nothing to compare an unmapped column against, so that
 * comparison cannot fail by construction.
 *
 * <p>That is not hypothetical. A {@code corpus_id} column on
 * {@code report_block_citations} sat in the schema, NOT NULL, with no default
 * and no entity mapping, for the whole life of the project. It surfaced when
 * synthesis was first executed and every synthesis failed with
 * {@code Field 'corpus_id' doesn't have a default value}. Nothing in the build
 * complained. This is the check that would have caught it on day one, and
 * {@code SchemaContractIntegrationTest} proves it by recreating the defect.
 *
 * <p><b>Flyway remains authoritative.</b> This reads the schema; it never
 * changes it. Hibernate schema generation is deliberately not used and must not
 * be introduced: a hand-written migration is a reviewable artifact and generated
 * DDL is not.
 *
 * <p>Detects:
 * <ul>
 *   <li>a NOT NULL column with no default that no entity maps -- the historical defect;</li>
 *   <li>a mapped column or table the database does not have;</li>
 *   <li>a missing critical index;</li>
 *   <li>a missing critical foreign key.</li>
 * </ul>
 *
 * <p>Intended to run in an integration test or a CI job, not on the request path:
 * a schema introspection query per table is cheap but not free, and it has no
 * business on a hot path.
 */
@Component
public class SchemaContractChecker {

    private static final Logger log = LoggerFactory.getLogger(SchemaContractChecker.class);

    /**
     * Columns that are legitimately absent from entity mappings.
     *
     * <p>Bookkeeping columns the application manages through SQL rather than
     * through a field. Everything else that is NOT NULL with no default and
     * unmapped is treated as a defect, because a column that important would
     * have been mapped. This list is deliberately short: widening it to make a
     * failure disappear would defeat the check.
     */
    private static final Set<String> EXPECTED_UNMAPPED = Set.of(
            "created_at", "updated_at", "content_hash");

    /** Tables with no entity mapping at all, and why. */
    private static final Set<String> UNMAPPED_TABLES = Set.of("flyway_schema_history");

    private final JdbcTemplate jdbc;
    private final EntityManagerFactory emf;

    public SchemaContractChecker(JdbcTemplate jdbc, EntityManagerFactory emf) {
        this.jdbc = jdbc;
        this.emf = emf;
    }

    public enum Severity { FAIL, WARN }

    /** One drift finding. */
    public record Violation(String table, String kind, String detail, Severity severity) {

        @Override
        public String toString() {
            return severity + " " + kind + " on " + table + ": " + detail;
        }
    }

    /** What the check found, and what it looked at so the run is auditable. */
    public record Result(List<Violation> violations, int tablesInspected, int columnsInspected,
                         int mappedColumns) {

        public boolean passed() {
            return failures().isEmpty();
        }

        public List<Violation> failures() {
            return violations.stream().filter(v -> v.severity() == Severity.FAIL).toList();
        }

        public List<Violation> warnings() {
            return violations.stream().filter(v -> v.severity() == Severity.WARN).toList();
        }
    }

    /** Every {@code table.column} the JPA metamodel knows about. */
    @Transactional(readOnly = true)
    public Map<String, Set<String>> mappedColumns() {
        Map<String, Set<String>> mapped = new TreeMap<>();
        var metamodel = emf.getMetamodel();

        for (var entity : metamodel.getEntities()) {
            Class<?> type = entity.getJavaType();
            String table = tableNameOf(type);
            Set<String> columns = new TreeSet<>();
            collect(type, metamodel, columns, 0);
            mapped.merge(table, columns, (a, b) -> {
                Set<String> merged = new TreeSet<>(a);
                merged.addAll(b);
                return merged;
            });
        }
        return mapped;
    }

    /**
     * Collects the column names of a type's basic attributes, descending one level
     * into embeddables.
     *
     * <p>Bounded at depth 2. PRISM has no nested embeddables and no collection of
     * embeddables, so a deeper structure is a sign the check needs rethinking
     * rather than something to guess at -- and silently stopping here is better
     * than reporting a false missing column.
     */
    private void collect(Class<?> type, jakarta.persistence.metamodel.Metamodel metamodel,
                         Set<String> columns, int depth) {
        for (var attr : metamodel.managedType(type).getAttributes()) {
            if (attr instanceof jakarta.persistence.metamodel.PluralAttribute<?, ?, ?> plural) {
                // A one-to-many or many-to-many owns a join table whose columns are
                // maintained by Hibernate, not written by this entity. Those tables
                // are covered by the mapped-table rule on the owning side.
                continue;
            }
            var singular = (jakarta.persistence.metamodel.SingularAttribute<?, ?>) attr;
            switch (singular.getPersistentAttributeType()) {
                case BASIC -> columns.add(columnNameOf(singular));
                case EMBEDDED -> {
                    if (depth < 2) {
                        collect(singular.getType().getJavaType(), metamodel, columns, depth + 1);
                    }
                }
                default -> addJoinColumn(singular, columns);
            }
        }
    }

    /**
     * Records the FK column a relationship owns.
     *
     * <p>This is not optional. A join column such as {@code document_id} is NOT a
     * BASIC attribute, so a naive mapping scan reports every NOT NULL foreign
     * key in the schema as an unmapped column. That is 70 false positives on the
     * real schema, and a check that cries wolf 70 times is a check people
     * disable.
     *
     * <p>Honours an explicit {@code @JoinColumn} name and otherwise applies the
     * JPA default of {@code <attribute>_id}, which is what the migrations use.
     */
    private void addJoinColumn(jakarta.persistence.metamodel.SingularAttribute<?, ?> attr,
                               Set<String> columns) {
        Object member = attr.getJavaMember();
        String explicit = null;
        if (member instanceof Field field) {
            var join = field.getAnnotation(jakarta.persistence.JoinColumn.class);
            if (join != null && !join.name().isBlank()) {
                explicit = join.name();
            }
        } else if (member instanceof Method method) {
            var join = method.getAnnotation(jakarta.persistence.JoinColumn.class);
            if (join != null && !join.name().isBlank()) {
                explicit = join.name();
            }
        }
        columns.add(explicit != null ? explicit : toColumnName(attr.getName()) + "_id");
    }
    private String tableNameOf(Class<?> type) {
        Table table = type.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        var entity = type.getAnnotation(jakarta.persistence.Entity.class);
        return entity != null && !entity.name().isBlank()
                ? entity.name() : type.getSimpleName().toLowerCase();
    }

    /**
     * Honours an explicit {@code @Column} name; otherwise applies the naming
     * strategy, which for this project is camelCase to snake_case.
     *
     * <p>Applying the strategy is not optional. A field like {@code resolutionState}
     * carries {@code @Column(nullable = false)} with no name, and Hibernate maps it
     * to {@code resolution_state}. Comparing the raw attribute name against
     * {@code information_schema} therefore reports a perfectly good mapping as a
     * missing column -- which is how the first version of this check produced a
     * false failure on a schema that is in fact correct.
     */
    private String columnNameOf(jakarta.persistence.metamodel.SingularAttribute<?, ?> attr) {
        String explicit = columnNameFromAnnotations(attr.getJavaMember());
        return explicit != null ? explicit : toColumnName(attr.getName());
    }

    /**
     * CamelCase to snake_case, matching Spring Boot's default naming strategy.
     *
     * <p>Inserts an underscore before an upper-case letter that follows a
     * lower-case letter or a digit, so {@code resolutionState} becomes
     * {@code resolution_state} and {@code claimId} becomes {@code claim_id}. A run
     * of capitals is kept together, so {@code parseURL} becomes {@code parse_url}
     * rather than {@code parse_u_r_l}.
     */
    static String toColumnName(String attribute) {
        StringBuilder out = new StringBuilder(attribute.length() + 4);
        for (int i = 0; i < attribute.length(); i++) {
            char c = attribute.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                char previous = attribute.charAt(i - 1);
                boolean previousIsLower = Character.isLowerCase(previous) || Character.isDigit(previous);
                boolean nextIsLower = i + 1 < attribute.length()
                        && Character.isLowerCase(attribute.charAt(i + 1));
                if (previousIsLower || nextIsLower) {
                    out.append('_');
                }
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    /**
     * The column name written in the annotation, or {@code null} if there is none.
     *
     * <p>Returning {@code null} rather than the field name is deliberate. A field
     * annotated {@code @Column(nullable = false)} states constraints but no name,
     * and Hibernate then derives {@code resolution_state} from {@code resolutionState}
     * through the naming strategy. Handing back the raw field name here would
     * short-circuit that derivation and report a correct mapping as missing.
     */
    private String columnNameFromAnnotations(Object member) {
        if (member instanceof Field field) {
            Column column = field.getAnnotation(Column.class);
            if (column != null && !column.name().isBlank()) {
                return column.name();
            }
        } else if (member instanceof Method method) {
            Column column = method.getAnnotation(Column.class);
            if (column != null && !column.name().isBlank()) {
                return column.name();
            }
        }
        return null;
    }

    /**
     * Indexes the application depends on and cannot work well without.
     *
     * <p>Not an exhaustive wish list. Each backs a query that degrades to a full
     * scan without it, so losing one is a performance defect rather than a
     * correctness one -- and a silent one, which is worse.
     */
    public static final Map<String, List<String>> CRITICAL_INDEXES = Map.ofEntries(
            Map.entry("document_chunks", List.of("ft_chunk_content", "ix_chunk_document_index")),
            Map.entry("claims", List.of("ix_claim_corpus", "ix_claim_status", "ix_claim_chunk")),
            Map.entry("verdicts", List.of("ix_verdict_claim", "ix_verdict_corpus")),
            Map.entry("contradictions", List.of("ix_contradiction_status", "ix_contradiction_corpus")),
            Map.entry("debates", List.of("ix_debate_state", "ix_debate_corpus")),
            Map.entry("trace_steps", List.of("ix_step_run_seq", "ix_step_parent")),
            Map.entry("entities", List.of("ix_entity_corpus")),
            Map.entry("triples", List.of("ix_triple_corpus_status", "ix_triple_subject",
                    "ix_triple_predicate")));
    /**
     * Columns that must carry a foreign key.
     *
     * <p>Without one, the graph can accumulate rows pointing at nothing and
     * nothing complains until a traversal dereferences it.
     */
    public static final Map<String, List<String>> CRITICAL_FOREIGN_KEYS = Map.ofEntries(
            Map.entry("document_chunks", List.of("document_id")),
            Map.entry("triples", List.of("source_chunk_id", "subject_entity_id", "object_entity_id")),
            Map.entry("claims", List.of("source_chunk_id")),
            Map.entry("verdicts", List.of("claim_id")),
            Map.entry("contradictions", List.of("left_claim_id", "right_claim_id")),
            Map.entry("debates", List.of("contradiction_id")),
            Map.entry("arguments", List.of("debate_id")),
            Map.entry("report_block_citations", List.of("report_id", "chunk_id")));
    /**
     * Runs the check. Read-only: it reports and never repairs, so the same class
     * can back a test, a CI job or an admin endpoint without knowing which.
     */
    @Transactional(readOnly = true)
    public Result check() {
        Map<String, Set<String>> mapped = mappedColumns();
        List<Violation> violations = new ArrayList<>();

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT table_name, column_name, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                ORDER BY table_name, column_name
                """);

        Map<String, Set<String>> databaseColumns = new TreeMap<>();
        int notNullUnmapped = 0;

        for (Map<String, Object> row : rows) {
            String table = text(row, "table_name", "TABLE_NAME");
            String column = text(row, "column_name", "COLUMN_NAME");
            String nullable = text(row, "is_nullable", "IS_NULLABLE");
            Object defaultValue = row.containsKey("column_default")
                    ? row.get("column_default") : row.get("COLUMN_DEFAULT");

            databaseColumns.computeIfAbsent(table, k -> new TreeSet<>()).add(column);

            if (!"NO".equalsIgnoreCase(nullable) || defaultValue != null) {
                continue;
            }
            if (UNMAPPED_TABLES.contains(table) || EXPECTED_UNMAPPED.contains(column)) {
                continue;
            }
            Set<String> known = mapped.get(table);
            if (known != null && known.contains(column)) {
                continue;
            }
            if (known == null) {
                // No entity owns this table, so there is no expectation to have
                // been violated: the column may be maintained entirely in SQL.
                // Recorded as a warning rather than a failure, because it is worth
                // a human glance but is not by itself a defect.
                violations.add(new Violation(table, "UNMAPPED_TABLE_COLUMN",
                        "column '" + column + "' is NOT NULL with no default on a table no entity "
                                + "maps. Not a failure, but confirm it is maintained deliberately.",
                        Severity.WARN));
                continue;
            }
            notNullUnmapped++;
            violations.add(new Violation(table, "UNMAPPED_NOT_NULL_COLUMN",
                    "column '" + column + "' is NOT NULL with no default and is not mapped by "
                            + "any entity field. ddl-auto=validate cannot detect this, and an "
                            + "INSERT that omits it fails at runtime with no build-time warning.",
                    Severity.FAIL));
        }

        // Mapped columns the database lacks. Hibernate catches these at startup,
        // but the check must stand alone so it is meaningful in a CI job that
        // never boots the application context.
        for (Map.Entry<String, Set<String>> entry : mapped.entrySet()) {
            Set<String> present = databaseColumns.get(entry.getKey());
            if (present == null) {
                violations.add(new Violation(entry.getKey(), "MISSING_TABLE",
                        "mapped by an entity but absent from the database", Severity.FAIL));
                continue;
            }
            for (String column : entry.getValue()) {
                if (!present.contains(column)) {
                    violations.add(new Violation(entry.getKey(), "MISSING_COLUMN",
                            "mapped column '" + column + "' is absent from the database",
                            Severity.FAIL));
                }
            }
        }

        Set<String> indexes = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT DISTINCT CONCAT(table_name, '|', index_name)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                """, String.class));
        CRITICAL_INDEXES.forEach((table, required) -> required.forEach(index -> {
            if (!indexes.contains(table + "|" + index)) {
                violations.add(new Violation(table, "MISSING_INDEX",
                        "critical index '" + index + "' is missing; the queries relying on it "
                                + "degrade to a full table scan without any error", Severity.FAIL));
            }
        }));

        Set<String> foreignKeys = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT DISTINCT CONCAT(table_name, '|', column_name)
                FROM information_schema.key_column_usage
                WHERE table_schema = DATABASE() AND referenced_table_name IS NOT NULL
                """, String.class));
        CRITICAL_FOREIGN_KEYS.forEach((table, required) -> required.forEach(column -> {
            if (!foreignKeys.contains(table + "|" + column)) {
                violations.add(new Violation(table, "MISSING_FOREIGN_KEY",
                        "column '" + column + "' carries no foreign key; the graph can accumulate "
                                + "rows pointing at nothing without any error", Severity.FAIL));
            }
        }));

        int mappedCount = mapped.values().stream().mapToInt(Set::size).sum();
        log.info("Schema contract: {} table(s), {} column(s) inspected, {} mapped, "
                        + "{} failure(s)", databaseColumns.size(), rows.size(), mappedCount,
                violations.size());

        return new Result(List.copyOf(violations), databaseColumns.size(), rows.size(), mappedCount);
    }

    private static String text(Map<String, Object> row, String lower, String upper) {
        Object value = row.get(lower) != null ? row.get(lower) : row.get(upper);
        return value == null ? "" : String.valueOf(value);
    }
}