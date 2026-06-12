package cero.ninja.agent.codexusage.store;

import cero.ninja.agent.codexusage.db.JdbcClient;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates every table the app owns, on the local {@code codex-usage-dashboard.sqlite}.
 *
 * <p>The pipeline is append-only and split into three writers that all target
 * this one file:
 * <ul>
 *   <li>{@code otel_log_records} — raw OTLP log records, stored verbatim as JSON
 *       by the receive path with no parsing or filtering;</li>
 *   <li>{@code annotated_events} — rows derived by the annotate job (parse raw +
 *       enrich from Codex's own DBs);</li>
 *   <li>{@code usage_samples} — periodic Codex rate-limit snapshots;</li>
 *   <li>{@code cursor} — forward-only cursors so each job resumes where it left
 *       off without gaps or double counting.</li>
 * </ul>
 */
@ApplicationScoped
public class SchemaInitializer {

    private static final double CODEX_USD_PER_CREDIT = 40.0 / 1000.0;

    private static final String RAW_TABLE = """
            CREATE TABLE IF NOT EXISTS otel_log_records (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              received_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
              record_json TEXT NOT NULL
            )
            """;

    private static final String RAW_RECEIVED_AT_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_otel_log_records_received_at
            ON otel_log_records(received_at)
            """;

    private static final String ANNOTATED_TABLE = """
            CREATE TABLE IF NOT EXISTS annotated_events (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              source_log_id INTEGER NOT NULL,
              source_tool TEXT NOT NULL DEFAULT 'codex',
              request_id TEXT,
              annotated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
              time_unix_nano INTEGER,
              event_name TEXT,
              thread_id TEXT,
              model TEXT,
              input_token_count INTEGER,
              cached_input_token_count INTEGER,
              output_token_count INTEGER,
              error_message TEXT,
              thread_model TEXT,
              thread_reasoning_effort TEXT,
              thread_source TEXT,
              thread_title TEXT,
              thread_cwd TEXT,
              service_tier TEXT,
              rate_model TEXT,
              input_credits REAL,
              cached_credits REAL,
              output_credits REAL,
              total_credits REAL,
              cost_usd REAL,
              input_cost_usd REAL,
              cached_input_cost_usd REAL,
              cache_creation_cost_usd REAL,
              cache_read_cost_usd REAL,
              output_cost_usd REAL,
              reported_cost_usd REAL,
              attributes_json TEXT
            )
            """;

    private static final String ANNOTATED_SOURCE_UNIQUE_INDEX = """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_annotated_events_source_unique
            ON annotated_events(source_log_id)
            """;

    private static final String ANNOTATED_EVENT_TIME_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_annotated_events_event_epoch
            ON annotated_events (
              CAST(COALESCE(NULLIF(time_unix_nano, 0) / 1000000000, strftime('%s', annotated_at)) AS INTEGER)
            )
            """;

    private static final String ANNOTATED_CREDIT_TIME_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_annotated_events_credit_epoch
            ON annotated_events (
              CAST(COALESCE(NULLIF(time_unix_nano, 0) / 1000000000, strftime('%s', annotated_at)) AS INTEGER)
            )
            WHERE total_credits IS NOT NULL
            """;

    private static final String ANNOTATED_REQUEST_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_annotated_events_source_request
            ON annotated_events(source_tool, request_id)
            WHERE request_id IS NOT NULL
            """;

    private static final String USAGE_TABLE = """
            CREATE TABLE IF NOT EXISTS usage_samples (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              sampled_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
              plan_type TEXT,
              window TEXT NOT NULL,
              used_percent REAL,
              remaining_percent REAL,
              resets_at INTEGER
            )
            """;

    private static final String USAGE_WINDOW_TIME_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_usage_samples_window_sampled
            ON usage_samples(window, sampled_at)
            """;

    private static final String USAGE_SAMPLED_AT_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_usage_samples_sampled_at
            ON usage_samples(sampled_at)
            """;

    private static final String CURSOR_TABLE = """
            CREATE TABLE IF NOT EXISTS cursor (
              name TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """;

    private static final String BACKFILL_CODEX_COST_USD = """
            UPDATE annotated_events
            SET cost_usd = round(total_credits * :usd_per_credit, 6)
            WHERE COALESCE(source_tool, 'codex') = 'codex'
              AND total_credits IS NOT NULL
              AND cost_usd IS NULL
            """;

    private static final String BACKFILL_CODEX_COMPONENT_COST_USD = """
            UPDATE annotated_events
            SET input_cost_usd = CASE
                    WHEN input_credits IS NULL THEN input_cost_usd
                    ELSE COALESCE(input_cost_usd, round(input_credits * :usd_per_credit, 6))
                END,
                cached_input_cost_usd = CASE
                    WHEN cached_credits IS NULL THEN cached_input_cost_usd
                    ELSE COALESCE(cached_input_cost_usd, round(cached_credits * :usd_per_credit, 6))
                END,
                output_cost_usd = CASE
                    WHEN output_credits IS NULL THEN output_cost_usd
                    ELSE COALESCE(output_cost_usd, round(output_credits * :usd_per_credit, 6))
                END
            WHERE COALESCE(source_tool, 'codex') = 'codex'
              AND (input_credits IS NOT NULL OR cached_credits IS NOT NULL OR output_credits IS NOT NULL)
            """;

    private static final String BACKFILL_CLAUDE_INPUT_TOTALS = """
            UPDATE annotated_events
            SET input_token_count = COALESCE(CAST(json_extract(attributes_json, '$.input_tokens') AS INTEGER), 0)
                                    + COALESCE(CAST(json_extract(attributes_json, '$.cache_creation_tokens') AS INTEGER), 0)
                                    + COALESCE(CAST(json_extract(attributes_json, '$.cache_read_tokens') AS INTEGER), 0),
                cached_input_token_count = COALESCE(CAST(json_extract(attributes_json, '$.cache_creation_tokens') AS INTEGER), 0)
                                           + COALESCE(CAST(json_extract(attributes_json, '$.cache_read_tokens') AS INTEGER), 0)
            WHERE COALESCE(source_tool, 'codex') = 'claude'
              AND attributes_json IS NOT NULL
              AND (json_extract(attributes_json, '$.input_tokens') IS NOT NULL
                   OR json_extract(attributes_json, '$.cache_creation_tokens') IS NOT NULL
                   OR json_extract(attributes_json, '$.cache_read_tokens') IS NOT NULL)
            """;

    private static final String DELETE_DUPLICATE_ANNOTATED_EVENTS = """
            DELETE FROM annotated_events
            WHERE id NOT IN (
              SELECT MIN(id)
              FROM annotated_events
              GROUP BY source_log_id
            )
            """;

    @Inject
    JdbcClient db;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    void init(@Observes StartupEvent event) {
        ensureParentDirectoryExists();
        db.sql(RAW_TABLE).update();
        db.sql(ANNOTATED_TABLE).update();
        db.sql(USAGE_TABLE).update();
        db.sql(CURSOR_TABLE).update();
        // Cross-tool fields added when Claude Code log support shipped.
        ensureColumn("annotated_events", "source_tool", "TEXT NOT NULL DEFAULT 'codex'");
        ensureColumn("annotated_events", "request_id", "TEXT");
        ensureColumn("annotated_events", "cost_usd", "REAL");
        // Credit columns added after annotated_events shipped — migrate older DBs.
        ensureColumn("annotated_events", "rate_model", "TEXT");
        ensureColumn("annotated_events", "input_credits", "REAL");
        ensureColumn("annotated_events", "cached_credits", "REAL");
        ensureColumn("annotated_events", "output_credits", "REAL");
        ensureColumn("annotated_events", "total_credits", "REAL");
        // USD component columns back cost-by-type views across tools.
        ensureColumn("annotated_events", "input_cost_usd", "REAL");
        ensureColumn("annotated_events", "cached_input_cost_usd", "REAL");
        ensureColumn("annotated_events", "cache_creation_cost_usd", "REAL");
        ensureColumn("annotated_events", "cache_read_cost_usd", "REAL");
        ensureColumn("annotated_events", "output_cost_usd", "REAL");
        ensureColumn("annotated_events", "reported_cost_usd", "REAL");
        // Attribution columns added later (trigger/originator/host).
        ensureColumn("annotated_events", "trigger", "TEXT");
        ensureColumn("annotated_events", "originator", "TEXT");
        ensureColumn("annotated_events", "host", "TEXT");
        db.sql(BACKFILL_CODEX_COST_USD)
                .param("usd_per_credit", CODEX_USD_PER_CREDIT)
                .update();
        db.sql(BACKFILL_CODEX_COMPONENT_COST_USD)
                .param("usd_per_credit", CODEX_USD_PER_CREDIT)
                .update();
        db.sql(BACKFILL_CLAUDE_INPUT_TOTALS).update();
        db.sql(DELETE_DUPLICATE_ANNOTATED_EVENTS).update();
        db.sql(ANNOTATED_SOURCE_UNIQUE_INDEX).update();
        db.sql(ANNOTATED_EVENT_TIME_INDEX).update();
        db.sql(ANNOTATED_CREDIT_TIME_INDEX).update();
        db.sql(ANNOTATED_REQUEST_INDEX).update();
        db.sql(RAW_RECEIVED_AT_INDEX).update();
        db.sql(USAGE_WINDOW_TIME_INDEX).update();
        db.sql(USAGE_SAMPLED_AT_INDEX).update();
    }

    private void ensureColumn(String table, String column, String type) {
        boolean exists = db.sql("SELECT 1 FROM pragma_table_info(:t) WHERE name = :c")
                .param("t", table)
                .param("c", column)
                .query((rs, row) -> 1)
                .optional()
                .isPresent();
        if (!exists) {
            db.sql("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type).update();
        }
    }

    private void ensureParentDirectoryExists() {
        String prefix = "jdbc:sqlite:";
        if (!jdbcUrl.startsWith(prefix)) {
            return;
        }
        String rawPath = jdbcUrl.substring(prefix.length());
        int queryIndex = rawPath.indexOf('?');
        String dbPath = queryIndex >= 0 ? rawPath.substring(0, queryIndex) : rawPath;
        Path parent = Path.of(dbPath).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SQLite directory: " + parent, e);
        }
    }
}
