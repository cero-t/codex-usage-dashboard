package cero.ninja.agent.codexusage.jobs;

import cero.ninja.agent.codexusage.db.JdbcClient;
import cero.ninja.agent.codexusage.store.Cursors;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(RetentionJobTest.Profile.class)
class RetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-06-12T00:00:00Z");

    @Inject
    JdbcClient db;

    @Inject
    Cursors cursors;

    @Inject
    RetentionJob retentionJob;

    @BeforeEach
    void resetTables() {
        db.sql("DELETE FROM cursor").update();
        db.sql("DELETE FROM annotated_events").update();
        db.sql("DELETE FROM usage_samples").update();
        db.sql("DELETE FROM otel_log_records").update();
    }

    @Test
    void defaultDerivedRetentionWindowsAreOneYear() {
        assertEquals("365d", RetentionJob.DEFAULT_ANNOTATED_EVENTS_RETENTION);
        assertEquals("365d", RetentionJob.DEFAULT_USAGE_SAMPLES_RETENTION);
    }

    @Test
    void keepsUnprocessedRawLogsEvenWhenOlderThanRetention() {
        insertRaw(1, "2026-06-10 00:00:00");
        insertRaw(2, "2026-06-10 00:00:00");
        insertRaw(3, "2026-06-11 12:00:00");
        cursors.setLong("annotate_log_id", 1);

        RetentionJob.CleanupResult result = retentionJob.cleanup(NOW);

        assertEquals(1, result.otelLogRecords());
        assertEquals(0, count("otel_log_records", 1));
        assertEquals(1, count("otel_log_records", 2));
        assertEquals(1, count("otel_log_records", 3));
    }

    @Test
    void deletesOldAnnotatedEventsAcrossSourceTools() {
        insertAnnotated(1, "codex", "2026-06-10 00:00:00", 0);
        insertAnnotated(2, "claude", "2026-06-12 00:00:00", epochNanos("2026-06-10T00:00:00Z"));
        insertAnnotated(3, "claude", "2026-06-11 12:00:00", 0);

        RetentionJob.CleanupResult result = retentionJob.cleanup(NOW);

        assertEquals(2, result.annotatedEvents());
        assertEquals(0, count("annotated_events", 1));
        assertEquals(0, count("annotated_events", 2));
        assertEquals(1, count("annotated_events", 3));
    }

    @Test
    void deletesOldUsageSamplesBySampledAt() {
        insertUsage(1, "2026-06-10 00:00:00");
        insertUsage(2, "2026-06-11 12:00:00");

        RetentionJob.CleanupResult result = retentionJob.cleanup(NOW);

        assertEquals(1, result.usageSamples());
        assertEquals(0, count("usage_samples", 1));
        assertEquals(1, count("usage_samples", 2));
    }

    private void insertRaw(long id, String receivedAt) {
        db.sql("""
                INSERT INTO otel_log_records (id, received_at, record_json)
                VALUES (:id, :received_at, '{}')
                """)
                .param("id", id)
                .param("received_at", receivedAt)
                .update();
    }

    private void insertAnnotated(long id, String sourceTool, String annotatedAt, long timeUnixNano) {
        db.sql("""
                INSERT INTO annotated_events (
                  id, source_log_id, source_tool, annotated_at, time_unix_nano, event_name
                ) VALUES (
                  :id, :source_log_id, :source_tool, :annotated_at, :time_unix_nano, 'api_request'
                )
                """)
                .param("id", id)
                .param("source_log_id", 10_000 + id)
                .param("source_tool", sourceTool)
                .param("annotated_at", annotatedAt)
                .param("time_unix_nano", timeUnixNano)
                .update();
    }

    private void insertUsage(long id, String sampledAt) {
        db.sql("""
                INSERT INTO usage_samples (id, sampled_at, window, used_percent, remaining_percent)
                VALUES (:id, :sampled_at, 'primary', 10.0, 90.0)
                """)
                .param("id", id)
                .param("sampled_at", sampledAt)
                .update();
    }

    private int count(String table, long id) {
        return db.sql("SELECT count(*) FROM " + table + " WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    private long epochNanos(String instant) {
        return Instant.parse(instant).getEpochSecond() * 1_000_000_000L;
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.datasource.jdbc.url",
                    "jdbc:sqlite:target/retention-job-test.sqlite?journal_mode=WAL&busy_timeout=10000",
                    "quarkus.http.test-port", "0",
                    "quarkus.scheduler.enabled", "false",
                    "codex-usage-dashboard.retention.otel-log-records", "1d",
                    "codex-usage-dashboard.retention.annotated-events", "1d",
                    "codex-usage-dashboard.retention.usage-samples", "1d");
        }
    }
}
