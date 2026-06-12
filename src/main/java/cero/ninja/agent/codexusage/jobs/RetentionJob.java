package cero.ninja.agent.codexusage.jobs;

import cero.ninja.agent.codexusage.db.JdbcClient;
import cero.ninja.agent.codexusage.store.Cursors;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes old local telemetry so the append-oriented SQLite database stays
 * bounded. Raw OTLP logs are only deleted after the annotate cursor has passed
 * them; that preserves unprocessed backlog even when it is older than the raw
 * retention window.
 */
@ApplicationScoped
public class RetentionJob {

    static final String DEFAULT_OTEL_LOG_RECORDS_RETENTION = "14d";
    static final String DEFAULT_ANNOTATED_EVENTS_RETENTION = "365d";
    static final String DEFAULT_USAGE_SAMPLES_RETENTION = "365d";

    private static final Logger LOG = Logger.getLogger(RetentionJob.class);
    private static final Pattern RETENTION_PATTERN = Pattern.compile("^(\\d+)(ms|s|m|h|d)$");
    private static final String ANNOTATED_EVENT_EPOCH = """
            CAST(COALESCE(NULLIF(time_unix_nano, 0) / 1000000000, strftime('%s', annotated_at)) AS INTEGER)
            """;

    private static final String DELETE_OTEL_LOG_RECORDS = """
            DELETE FROM otel_log_records
            WHERE id <= :annotate_cursor
              AND received_at < datetime(:cutoff_epoch, 'unixepoch')
            """;

    private static final String DELETE_ANNOTATED_EVENTS = """
            DELETE FROM annotated_events
            WHERE %s < :cutoff_epoch
            """.formatted(ANNOTATED_EVENT_EPOCH);

    private static final String DELETE_USAGE_SAMPLES = """
            DELETE FROM usage_samples
            WHERE sampled_at < datetime(:cutoff_epoch, 'unixepoch')
            """;

    @Inject
    JdbcClient db;

    @Inject
    Cursors cursors;

    @ConfigProperty(name = "codex-usage-dashboard.retention.otel-log-records",
            defaultValue = DEFAULT_OTEL_LOG_RECORDS_RETENTION)
    String otelLogRecordsRetentionConfig;

    @ConfigProperty(name = "codex-usage-dashboard.retention.annotated-events",
            defaultValue = DEFAULT_ANNOTATED_EVENTS_RETENTION)
    String annotatedEventsRetentionConfig;

    @ConfigProperty(name = "codex-usage-dashboard.retention.usage-samples",
            defaultValue = DEFAULT_USAGE_SAMPLES_RETENTION)
    String usageSamplesRetentionConfig;

    private RetentionWindow otelLogRecordsRetention;
    private RetentionWindow annotatedEventsRetention;
    private RetentionWindow usageSamplesRetention;

    @PostConstruct
    void validateConfig() {
        otelLogRecordsRetention = RetentionWindow.parse(
                "codex-usage-dashboard.retention.otel-log-records", otelLogRecordsRetentionConfig);
        annotatedEventsRetention = RetentionWindow.parse(
                "codex-usage-dashboard.retention.annotated-events", annotatedEventsRetentionConfig);
        usageSamplesRetention = RetentionWindow.parse(
                "codex-usage-dashboard.retention.usage-samples", usageSamplesRetentionConfig);
    }

    @Scheduled(every = "{codex-usage-dashboard.retention.every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void run() {
        CleanupResult deleted = cleanup(Instant.now());
        if (deleted.total() > 0) {
            LOG.infof("retention: deleted raw=%d annotated=%d usage=%d row(s)",
                    deleted.otelLogRecords(), deleted.annotatedEvents(), deleted.usageSamples());
        }
    }

    CleanupResult cleanup(Instant now) {
        int rawDeleted = deleteOtelLogRecords(now);
        int annotatedDeleted = deleteAnnotatedEvents(now);
        int usageDeleted = deleteUsageSamples(now);
        return new CleanupResult(rawDeleted, annotatedDeleted, usageDeleted);
    }

    private int deleteOtelLogRecords(Instant now) {
        if (!otelLogRecordsRetention.enabled()) {
            return 0;
        }
        long annotateCursor = cursors.getLong("annotate_log_id", 0);
        return db.sql(DELETE_OTEL_LOG_RECORDS)
                .param("annotate_cursor", annotateCursor)
                .param("cutoff_epoch", otelLogRecordsRetention.cutoffEpoch(now))
                .update();
    }

    private int deleteAnnotatedEvents(Instant now) {
        if (!annotatedEventsRetention.enabled()) {
            return 0;
        }
        return db.sql(DELETE_ANNOTATED_EVENTS)
                .param("cutoff_epoch", annotatedEventsRetention.cutoffEpoch(now))
                .update();
    }

    private int deleteUsageSamples(Instant now) {
        if (!usageSamplesRetention.enabled()) {
            return 0;
        }
        return db.sql(DELETE_USAGE_SAMPLES)
                .param("cutoff_epoch", usageSamplesRetention.cutoffEpoch(now))
                .update();
    }

    record CleanupResult(int otelLogRecords, int annotatedEvents, int usageSamples) {
        int total() {
            return otelLogRecords + annotatedEvents + usageSamples;
        }
    }

    private record RetentionWindow(Duration duration) {
        static RetentionWindow parse(String propertyName, String rawValue) {
            String value = rawValue == null ? "" : rawValue.trim().toLowerCase(Locale.ROOT);
            if (value.isBlank() || value.equals("0") || value.equals("disabled")
                    || value.equals("off") || value.equals("none")) {
                return new RetentionWindow(Duration.ZERO);
            }
            if (value.startsWith("p")) {
                return new RetentionWindow(Duration.parse(value.toUpperCase(Locale.ROOT)));
            }
            Matcher matcher = RETENTION_PATTERN.matcher(value);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(propertyName
                        + " must be a duration like 14d, 365d, 12h, 30m, 60s, 500ms, or disabled");
            }
            long amount = Long.parseLong(matcher.group(1));
            Duration duration = switch (matcher.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalStateException("Unexpected duration unit");
            };
            return new RetentionWindow(duration);
        }

        boolean enabled() {
            return !duration.isZero() && !duration.isNegative();
        }

        long cutoffEpoch(Instant now) {
            return now.minus(duration).getEpochSecond();
        }
    }
}
