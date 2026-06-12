package cero.ninja.agent.codexusage.otel;

import cero.ninja.agent.codexusage.db.JdbcClient;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(RawLogStoreDropFilterTest.Profile.class)
class RawLogStoreDropFilterTest {

    @Inject
    RawLogStore store;

    @Inject
    JdbcClient db;

    @BeforeEach
    void resetTables() {
        db.sql("DELETE FROM otel_log_records").update();
    }

    @Test
    void dropsStreamingDeltaRecordsButStoresTheRest() {
        store.store(requestWith(
                recordWithEventKind("response.output_text.delta"),
                recordWithEventKind("response.custom_tool_call_input.delta"),
                recordWithEventKind("response.completed"),
                LogRecord.newBuilder().setBody(AnyValue.newBuilder().setStringValue("claude_code.api_request")).build()));

        List<String> stored = db.sql("SELECT record_json FROM otel_log_records ORDER BY id")
                .query((rs, row) -> rs.getString(1))
                .list();

        assertEquals(2, stored.size());
        assertEquals(true, stored.get(0).contains("response.completed"));
        assertEquals(true, stored.get(1).contains("claude_code.api_request"));
    }

    private static LogRecord recordWithEventKind(String kind) {
        return LogRecord.newBuilder()
                .addAttributes(KeyValue.newBuilder()
                        .setKey("event.kind")
                        .setValue(AnyValue.newBuilder().setStringValue(kind)))
                .build();
    }

    private static ExportLogsServiceRequest requestWith(LogRecord... records) {
        ScopeLogs.Builder scopeLogs = ScopeLogs.newBuilder();
        for (LogRecord record : records) {
            scopeLogs.addLogRecords(record);
        }
        return ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(scopeLogs))
                .build();
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.datasource.jdbc.url",
                    "jdbc:sqlite:target/raw-log-store-drop-filter-test.sqlite?journal_mode=WAL&busy_timeout=10000",
                    "quarkus.http.test-port", "0",
                    "quarkus.scheduler.enabled", "false");
        }
    }
}
