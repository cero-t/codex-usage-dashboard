package cero.ninja.agent.codexusage.jobs;

import cero.ninja.agent.codexusage.codex.CodexDb;
import cero.ninja.agent.codexusage.credit.ClaudeRateCard;
import cero.ninja.agent.codexusage.credit.RateCard;
import cero.ninja.agent.codexusage.db.JdbcClient;
import cero.ninja.agent.codexusage.store.Cursors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Job A. Reads raw OTLP log rows in id order from a forward-only cursor, parses
 * each, keeps the token-usage / error ones, enriches them from Codex's own DBs
 * (thread metadata from {@code state_5}, trigger signatures from {@code logs_2}),
 * and appends a derived row to {@code annotated_events}. The raw row is never
 * mutated; replaying an already annotated row requires deleting the derived row
 * first because {@code source_log_id} is unique.
 */
@ApplicationScoped
public class AnnotateJob {

    private static final Logger LOG = Logger.getLogger(AnnotateJob.class);
    private static final String CURSOR = "annotate_log_id";
    private static final double CODEX_USD_PER_CREDIT = 40.0 / 1000.0;

    // Trigger classification signatures. A turn the user didn't drive (not in
    // state_5.threads) is tagged ambient / memory / background by scanning its
    // logs_2 bodies. The ambient-suggestions JSON is usually embedded escaped
    // inside a Text { text: "..." } body, so we match both escaped and bare forms.
    private static final String[] AMBIENT_SIGNATURES = {
            "ambient suggestions", "\"suggestions\":[", "\\\"suggestions\\\":["
    };
    private static final String[] MEMORY_SIGNATURES = {
            "/memories/", "MEMORY.md", "memory_summary.md"
    };

    private static final String SELECT_RAW = """
            SELECT id, record_json FROM otel_log_records
            WHERE id > :cursor ORDER BY id ASC LIMIT :limit
            """;

    private static final String INSERT_ANNOTATED = """
            INSERT INTO annotated_events (
              source_log_id, source_tool, request_id, time_unix_nano, event_name, thread_id, model,
              input_token_count, cached_input_token_count, output_token_count,
              error_message, thread_model, thread_reasoning_effort, thread_source,
              thread_title, thread_cwd, service_tier, rate_model,
              input_credits, cached_credits, output_credits, total_credits, cost_usd,
              input_cost_usd, cached_input_cost_usd, cache_creation_cost_usd, cache_read_cost_usd,
              output_cost_usd, reported_cost_usd,
              trigger, originator, host, attributes_json
            ) VALUES (
              :source_log_id, :source_tool, :request_id, :time_unix_nano, :event_name, :thread_id, :model,
              :input_token_count, :cached_input_token_count, :output_token_count,
              :error_message, :thread_model, :thread_reasoning_effort, :thread_source,
              :thread_title, :thread_cwd, :service_tier, :rate_model,
              :input_credits, :cached_credits, :output_credits, :total_credits, :cost_usd,
              :input_cost_usd, :cached_input_cost_usd, :cache_creation_cost_usd, :cache_read_cost_usd,
              :output_cost_usd, :reported_cost_usd,
              :trigger, :originator, :host, :attributes_json
            )
            ON CONFLICT(source_log_id) DO NOTHING
            """;

    private static final String SELECT_ANNOTATED_REQUEST = """
            SELECT 1 FROM annotated_events
            WHERE source_tool = :source_tool AND request_id = :request_id
            LIMIT 1
            """;

    private static final String SELECT_CLAUDE_USER_PROMPT = """
            SELECT 1 FROM otel_log_records
            WHERE json_extract(record_json, '$.attributes."prompt.id"') = :prompt_id
              AND (
                json_extract(record_json, '$.attributes."event.name"') = 'user_prompt'
                OR json_extract(record_json, '$.body') = 'claude_code.user_prompt'
              )
            LIMIT 1
            """;

    @Inject
    JdbcClient db;

    @Inject
    Cursors cursors;

    @Inject
    CodexDb codex;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RateCard rateCard;

    @Inject
    ClaudeRateCard claudeRateCard;

    @ConfigProperty(name = "codex-usage-dashboard.annotate.batch-size", defaultValue = "500")
    int batchSize;

    @ConfigProperty(name = "codex-usage-dashboard.codex.enabled", defaultValue = "true")
    boolean codexEnabled;

    @ConfigProperty(name = "codex-usage-dashboard.claude.enabled", defaultValue = "true")
    boolean claudeEnabled;

    @Scheduled(every = "{codex-usage-dashboard.annotate.every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void run() {
        long cursor = cursors.getLong(CURSOR, 0);
        List<RawRow> rows = db.sql(SELECT_RAW)
                .param("cursor", cursor)
                .param("limit", batchSize)
                .query(RawRow.class)
                .list();
        if (rows.isEmpty()) {
            return;
        }

        Map<String, Optional<CodexDb.ThreadInfo>> threadCache = new HashMap<>();
        Map<String, String> triggerCache = new HashMap<>();
        Map<String, Boolean> claudeUserPromptCache = new HashMap<>();
        long lastId = cursor;
        long failedId = -1;
        int processed = 0;
        int annotated = 0;
        try (CodexConnections connections = new CodexConnections()) {
            for (RawRow row : rows) {
                try {
                    if (annotateOne(row, connections, threadCache, triggerCache, claudeUserPromptCache)) {
                        annotated++;
                    }
                    lastId = row.id();
                    processed++;
                } catch (Exception e) {
                    // Raw stays intact for replay. Stop before this row so parser/DB bugs
                    // remain recoverable without manual cursor rewind.
                    failedId = row.id();
                    LOG.warnf("annotate failed for raw id=%d; cursor remains at %d and row will be retried next pass: %s",
                            failedId, lastId, e.getMessage());
                    break;
                }
            }
        }

        if (lastId != cursor) {
            cursors.setLong(CURSOR, lastId);
        }
        if (failedId >= 0) {
            LOG.infof("annotate: stopped before raw id=%d after %d processed row(s), %d annotated (cursor=%d)",
                    failedId, processed, annotated, lastId);
        } else if (annotated > 0) {
            LOG.infof("annotate: %d/%d processed rows -> annotated_events (cursor=%d)",
                    annotated, processed, lastId);
        }
    }

    /** Returns true if a derived row was appended. */
    private boolean annotateOne(
            RawRow row,
            CodexConnections connections,
            Map<String, Optional<CodexDb.ThreadInfo>> threadCache,
            Map<String, String> triggerCache,
            Map<String, Boolean> claudeUserPromptCache
    ) throws Exception {
        JsonNode root = objectMapper.readTree(row.recordJson());
        JsonNode attrs = root.path("attributes");
        JsonNode resource = root.path("resource_attributes");

        if (isClaudeRecord(root, resource)) {
            if (!claudeEnabled) {
                return false;
            }
            return annotateClaude(row, root, attrs, resource, claudeUserPromptCache);
        }

        if (!codexEnabled) {
            return false;
        }

        Long inputTokens = optLong(attrs, "input_token_count");
        String errorMessage = optString(attrs, "error.message");
        if (inputTokens == null && (errorMessage == null || errorMessage.isBlank())) {
            return false; // not a token-usage / error record — decided, skipped
        }

        // The Codex OTel export carries the thread identifier as conversation.id;
        // it joins to state_5.threads.id and logs_2.logs.thread_id. (Older guess
        // "thread_id" is kept as a fallback but is not what Codex emits.)
        String threadId = firstNonBlank(optString(attrs, "conversation.id"),
                optString(attrs, "thread_id"),
                optString(resource, "thread_id"));

        Optional<CodexDb.ThreadInfo> thread;
        if (threadId == null) {
            thread = Optional.empty();
        } else if (threadCache.containsKey(threadId)) {
            thread = threadCache.get(threadId);
        } else {
            thread = codex.lookupThread(connections.state5(), threadId);
            threadCache.put(threadId, thread);
        }

        // Attribute the turn (user / ambient / memory / background).
        // Cached per thread within the pass.
        String trigger = classifyTrigger(threadId, thread.isPresent(), connections.logs2(), triggerCache);

        CodexDb.ThreadInfo ti = thread.orElse(null);
        String eventModel = optString(attrs, "model");
        Long cachedTokens = optLong(attrs, "cached_token_count");
        Long outputTokens = optLong(attrs, "output_token_count");

        // Bill against the event's model, falling back to the thread's model.
        // Credits are always standard-rate: OTLP completion rows do not carry
        // turn_id/submission_id, so there is no reliable per-turn service tier to
        // surcharge a Fast/priority turn (see dev_docs/codex-data-model.md).
        String rateModel = firstNonBlank(eventModel, ti == null ? null : ti.model());
        RateCard.Credits credits = inputTokens == null
                ? null
                : rateCard.compute(rateModel, inputTokens, cachedTokens, outputTokens);

        return db.sql(INSERT_ANNOTATED)
                .param("source_log_id", row.id())
                .param("source_tool", "codex")
                .param("request_id", null)
                .param("time_unix_nano", resolveTimeNano(root))
                .param("event_name", optString(attrs, "event.name"))
                .param("thread_id", threadId)
                .param("model", eventModel)
                .param("input_token_count", inputTokens)
                .param("cached_input_token_count", cachedTokens)
                .param("output_token_count", outputTokens)
                .param("error_message", errorMessage)
                .param("thread_model", ti == null ? null : ti.model())
                .param("thread_reasoning_effort", ti == null ? null : ti.reasoningEffort())
                .param("thread_source", ti == null ? null : ti.source())
                .param("thread_title", ti == null ? null : ti.title())
                .param("thread_cwd", ti == null ? null : ti.cwd())
                .param("service_tier", null)
                .param("rate_model", rateModel)
                .param("input_credits", credits == null ? null : credits.input())
                .param("cached_credits", credits == null ? null : credits.cached())
                .param("output_credits", credits == null ? null : credits.output())
                .param("total_credits", credits == null ? null : credits.total())
                .param("cost_usd", codexCostUsd(credits))
                .param("input_cost_usd", credits == null ? null : componentCostUsd(credits.input()))
                .param("cached_input_cost_usd", credits == null ? null : componentCostUsd(credits.cached()))
                .param("cache_creation_cost_usd", null)
                .param("cache_read_cost_usd", null)
                .param("output_cost_usd", credits == null ? null : componentCostUsd(credits.output()))
                .param("reported_cost_usd", null)
                .param("trigger", trigger)
                .param("originator", optString(attrs, "originator"))
                .param("host", optString(resource, "host.name"))
                .param("attributes_json", attrs.isMissingNode() ? null : attrs.toString())
                .update() > 0;
    }

    private boolean annotateClaude(
            RawRow row,
            JsonNode root,
            JsonNode attrs,
            JsonNode resource,
            Map<String, Boolean> claudeUserPromptCache
    ) {
        String rawEvent = firstNonBlank(optString(attrs, "event.name"), optString(root, "body"));
        String eventName = rawEvent == null ? null : "claude." + rawEvent.replaceFirst("^claude_code\\.", "");
        String requestId = optString(attrs, "request_id");
        Long inputTokens = optLong(attrs, "input_tokens");
        Long cacheCreationTokens = optLong(attrs, "cache_creation_tokens");
        Long cacheReadTokens = optLong(attrs, "cache_read_tokens");
        Long outputTokens = optLong(attrs, "output_tokens");
        Double reportedCostUsd = firstNonNull(optDouble(attrs, "cost_usd"),
                microsToUsd(optLong(attrs, "cost_usd_micros")));
        String errorMessage = firstNonBlank(optString(attrs, "error"),
                optString(attrs, "error.message"),
                optString(attrs, "error_name"));

        boolean hasUsage = inputTokens != null || cacheCreationTokens != null
                || cacheReadTokens != null || outputTokens != null || reportedCostUsd != null;
        if (!hasUsage && (errorMessage == null || errorMessage.isBlank())) {
            return false;
        }
        if (requestId != null && alreadyAnnotated("claude", requestId)) {
            return false;
        }

        Long cachedTokens = sumNullable(cacheCreationTokens, cacheReadTokens);
        Long totalInputTokens = sumNullable(inputTokens, cacheCreationTokens, cacheReadTokens);
        String model = optString(attrs, "model");
        ClaudeRateCard.Costs costs = hasBillableClaudeTokens(inputTokens, cacheCreationTokens, cacheReadTokens, outputTokens)
                ? claudeRateCard.compute(model, inputTokens, cacheCreationTokens, cacheReadTokens, outputTokens)
                : null;
        Double costUsd = costs == null ? reportedCostUsd : Double.valueOf(costs.total());
        String querySource = optString(attrs, "query_source");
        String agentName = optString(attrs, "agent.name");
        String promptId = optString(attrs, "prompt.id");
        String serviceName = optString(resource, "service.name");

        return db.sql(INSERT_ANNOTATED)
                .param("source_log_id", row.id())
                .param("source_tool", "claude")
                .param("request_id", requestId)
                .param("time_unix_nano", resolveTimeNano(root))
                .param("event_name", eventName)
                .param("thread_id", optString(attrs, "session.id"))
                .param("model", model)
                .param("input_token_count", totalInputTokens)
                .param("cached_input_token_count", cachedTokens)
                .param("output_token_count", outputTokens)
                .param("error_message", errorMessage)
                .param("thread_model", null)
                .param("thread_reasoning_effort", optString(attrs, "effort"))
                .param("thread_source", firstNonBlank(optString(attrs, "app.entrypoint"), serviceName))
                .param("thread_title", null)
                .param("thread_cwd", null)
                .param("service_tier", firstNonBlank(optString(attrs, "speed"), optString(attrs, "service_tier")))
                .param("rate_model", model)
                .param("input_credits", null)
                .param("cached_credits", null)
                .param("output_credits", null)
                .param("total_credits", null)
                .param("cost_usd", costUsd)
                .param("input_cost_usd", costs == null ? null : costs.input())
                .param("cached_input_cost_usd", null)
                .param("cache_creation_cost_usd", costs == null ? null : costs.cacheCreation())
                .param("cache_read_cost_usd", costs == null ? null : costs.cacheRead())
                .param("output_cost_usd", costs == null ? null : costs.output())
                .param("reported_cost_usd", reportedCostUsd)
                .param("trigger", classifyClaudeTrigger(querySource, agentName,
                        claudeHasUserPrompt(promptId, claudeUserPromptCache)))
                .param("originator", firstNonBlank(agentName, querySource))
                .param("host", optString(resource, "host.name"))
                .param("attributes_json", attrs.isMissingNode() ? null : attrs.toString())
                .update() > 0;
    }

    private boolean alreadyAnnotated(String sourceTool, String requestId) {
        return db.sql(SELECT_ANNOTATED_REQUEST)
                .param("source_tool", sourceTool)
                .param("request_id", requestId)
                .query((rs, row) -> 1)
                .optional()
                .isPresent();
    }

    private static boolean isClaudeRecord(JsonNode root, JsonNode resource) {
        String serviceName = optString(resource, "service.name");
        if (serviceName != null && serviceName.startsWith("claude-code")) {
            return true;
        }
        String body = optString(root, "body");
        return body != null && (body.startsWith("claude_code.") || body.equals("api_request") || body.equals("api_error"));
    }

    private boolean claudeHasUserPrompt(String promptId, Map<String, Boolean> cache) {
        if (promptId == null || promptId.isBlank()) {
            return false;
        }
        return cache.computeIfAbsent(promptId, pid -> db.sql(SELECT_CLAUDE_USER_PROMPT)
                .param("prompt_id", pid)
                .query((rs, row) -> true)
                .optional()
                .orElse(false));
    }

    private static String classifyClaudeTrigger(String querySource, String agentName, boolean hasUserPrompt) {
        if ("generate_session_title".equals(querySource)) {
            return "user_driven_agent";
        }
        if (agentName != null || (querySource != null && querySource.startsWith("agent:"))) {
            return hasUserPrompt ? "user_driven_agent" : "agent";
        }
        return "user";
    }

    /**
     * Attribute a turn to its trigger: null thread → background; thread present
     * in state_5 → user; otherwise the logs_2 body decides ambient / memory,
     * defaulting to background.
     */
    private String classifyTrigger(String threadId, boolean knownThread, Connection logs2,
                                   Map<String, String> cache) {
        if (threadId == null || threadId.isBlank()) {
            return "background";
        }
        if (knownThread) {
            return "user";
        }
        return cache.computeIfAbsent(threadId, t -> {
            if (codex.threadHasSignature(logs2, t, AMBIENT_SIGNATURES)) {
                return "ambient";
            }
            if (codex.threadHasSignature(logs2, t, MEMORY_SIGNATURES)) {
                return "memory";
            }
            return "background";
        });
    }

    /**
     * Real event time in epoch-nanos. The OTel records carry {@code time_unix_nano}
     * = 0 (Codex doesn't set it); the wall-clock is in {@code observed_time_unix_nano}.
     */
    private static Long resolveTimeNano(JsonNode root) {
        Long t = optLong(root, "time_unix_nano");
        if (t != null && t > 0) {
            return t;
        }
        return optLong(root, "observed_time_unix_nano");
    }

    private static Long optLong(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        if (v.isTextual() && !v.asText().isBlank()) {
            try {
                return Long.parseLong(v.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double optDouble(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isTextual() && !v.asText().isBlank()) {
            try {
                return Double.parseDouble(v.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String optString(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.isValueNode() ? v.asText() : v.toString();
        return s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long sumNullable(Long... values) {
        long total = 0;
        boolean any = false;
        for (Long value : values) {
            if (value != null) {
                total += value;
                any = true;
            }
        }
        return any ? total : null;
    }

    private static Double microsToUsd(Long micros) {
        return micros == null ? null : micros / 1_000_000.0;
    }

    private static Double codexCostUsd(RateCard.Credits credits) {
        if (credits == null) {
            return null;
        }
        return Math.round(credits.total() * CODEX_USD_PER_CREDIT * 1_000_000.0) / 1_000_000.0;
    }

    private static Double componentCostUsd(double credits) {
        return Math.round(credits * CODEX_USD_PER_CREDIT * 1_000_000.0) / 1_000_000.0;
    }

    private static boolean hasBillableClaudeTokens(Long... values) {
        for (Long value : values) {
            if (value != null && value > 0) {
                return true;
            }
        }
        return false;
    }

    private static void close(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // best-effort
            }
        }
    }

    public record RawRow(long id, String recordJson) {}

    private final class CodexConnections implements AutoCloseable {
        private Connection state5;
        private Connection logs2;
        private boolean logs2Attempted;

        Connection state5() throws SQLException {
            if (state5 == null) {
                state5 = codex.openState5();
            }
            return state5;
        }

        Connection logs2() {
            if (logs2Attempted) {
                return logs2;
            }
            logs2Attempted = true;
            try {
                logs2 = codex.openLogs2();
            } catch (SQLException e) {
                LOG.debugf("logs_2 unavailable this pass, trigger falls back where needed: %s", e.getMessage());
            }
            return logs2;
        }

        @Override
        public void close() {
            AnnotateJob.close(state5);
            AnnotateJob.close(logs2);
        }
    }
}
