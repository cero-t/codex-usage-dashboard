# Architecture

`codex-usage-dashboard` is a local append-oriented pipeline:

```text
Codex OTLP logs / Claude Code OTLP logs
  -> OTLP receiver
  -> otel_log_records
  -> annotate job
  -> annotated_events
  -> JSON API + static dashboard

Codex local SQLite
  -> annotate job (Codex rows only)
  -> annotated_events

codex app-server rateLimits/read
  -> usage job
  -> usage_samples
  -> JSON API + static dashboard
```

## Receive Path

Implemented by:

- `http/OtlpHttpResource`
- `otel/OtlpGrpcReceiver`
- `otel/RawLogStore`

Supported inputs:

- OTLP/gRPC on `:4317`
- OTLP/HTTP protobuf on `:4318/v1/logs`
- gzip-compressed OTLP/HTTP protobuf

Traces and metrics are accepted and discarded. Logs are converted mechanically
from protobuf to JSON and appended to `otel_log_records(record_json)`.

Claude Code cost and token charts use its log/event `api_request` records. The
dashboard does not persist Claude Code metrics or traces.

OTLP/HTTP JSON is intentionally not implemented. JSON requests return `415` so
an exporter configured for JSON cannot appear healthy while dropping data.

## Annotate Job

Implemented by `jobs/AnnotateJob`, scheduled every 60 seconds by default.

The job reads `otel_log_records` after cursor `annotate_log_id`, parses raw JSON,
and keeps rows that match either tool's usage/error shape.

Codex rows are kept when they contain either:

- `attributes.input_token_count`
- `attributes.error.message`

Codex rows are enriched from Codex local SQLite:

- `~/.codex/state_5.sqlite`, table `threads`
- `~/.codex/logs_2.sqlite`, table `logs`

Claude Code rows are kept when `resource_attributes.service.name` starts with
`claude-code`, or the log body/event name identifies a Claude Code API event.
The relevant usage shape is:

- `api_request` with token fields and/or `cost_usd`
- `api_error` or another Claude Code API event with an error field

The derived row is appended to `annotated_events`; raw rows are never mutated.
Each row has `source_tool = 'codex'` or `source_tool = 'claude'`. Claude Code
`request_id` is used for source-level dedupe when present. Codex rows compute
`total_credits` and an estimated `cost_usd`; Claude rows compute `cost_usd`
and per-type USD component columns from the Claude API pricing table while
preserving the log-provided value in `reported_cost_usd`. Claude
`cache_creation_tokens` are treated as 5-minute cache writes, and
`total_credits` stays null.

If a per-row annotate exception occurs, the pass stops before that row and leaves
the cursor at the last successfully processed raw row. The failing row is retried
on the next pass so parser/DB bugs remain replayable without manual cursor rewind.

## Usage Job

Implemented by `jobs/UsageJob`, scheduled every 60 seconds by default.

Usage is not in Codex SQLite. The job launches:

```sh
codex app-server --listen stdio://
```

and sends JSON-RPC:

```text
initialize
initialized
account/rateLimits/read
```

Each returned primary/secondary usage window is appended to `usage_samples`.

## Tables

Owned database:

```text
data/codex-usage-dashboard.sqlite
```

Tables:

- `otel_log_records`: raw OTLP log records as JSON
- `annotated_events`: parsed token/error rows plus source, enrichment, credits,
  and cost
- `usage_samples`: point-in-time rate-limit snapshots
- `cursor`: forward cursor state

Retention:

- `codex-usage-dashboard.retention.otel-log-records=14d` deletes old raw OTLP
  rows by `received_at`, but only when `id <= cursor['annotate_log_id']`.
  Unprocessed raw backlog is never deleted by retention.
- `codex-usage-dashboard.retention.annotated-events=365d` deletes old derived
  Codex and Claude Code rows by event time, falling back to `annotated_at` when
  `time_unix_nano` is missing or zero.
- `codex-usage-dashboard.retention.usage-samples=365d` deletes old Codex
  rate-limit snapshots by `sampled_at`.
- `codex-usage-dashboard.retention.every=1h` controls the cleanup cadence. Any
  table retention value can be `0` or `disabled` to keep that table indefinitely.

Important indexes:

- `idx_annotated_events_source_unique`: raw-to-derived uniqueness
- `idx_annotated_events_event_epoch`: dashboard time filtering
- `idx_annotated_events_credit_epoch`: credit time filtering
- `idx_annotated_events_source_request`: Claude Code request-id dedupe
- `idx_otel_log_records_received_at`: raw-log retention cutoff
- `idx_usage_samples_window_sampled`: usage history by window/time
- `idx_usage_samples_sampled_at`: usage retention cutoff

## Dashboard

The dashboard is a static `index.html` served from:

```text
src/main/resources/META-INF/resources/
```

It uses vendored Apache ECharts and calls JSON endpoints under `/api`.

Dashboard time-series endpoints accept:

```text
source=codex|claude
range=15m|30m|1h|3h|6h|12h|1d|3d|1w|30d|6mo
from=<epoch seconds or milliseconds>
to=<epoch seconds or milliseconds>
grain=1m|5m|30m|1h|12h|1d
```

Metric display mode is a client-side UI state. The page preserves per-panel
choices in the URL with:

```text
typeTimeMode=cost|tokens
triggerTimeMode=cost|tokens
modelMode=cost|tokens
triggerMode=cost|tokens
modelTriggerMode=cost|tokens
conversationsMode=cost|tokens
recentMode=cost|tokens
```

Default:

```text
source=codex
range=6h
grain=5m
```

`from` and `to` override the relative `range`. The UI's `Current 5h window` and
`Custom` range options are client-side states: before calling the API, the page
resolves them into explicit `from`/`to` values.

Both Codex and Claude Code tabs can render most breakdown panels by USD cost or
raw token count. Cost mode uses `/api/cost/...`; token mode uses `/api/tokens/...`.
The Codex type chart keeps its usage % overlays in both modes. Conversation
totals, recent completions, and recent errors are source-filtered shared tables.

## Security Posture

The default bind address is `127.0.0.1`.

This is deliberate. The dashboard exposes local Codex metadata, and
`/api/events/{id}/raw` returns the raw OTLP record behind a derived row. Raw OTLP
can include account identifiers, email addresses, host names, conversation ids,
and request/error metadata.

LAN access should be an explicit user choice via:

```sh
QUARKUS_HTTP_HOST=0.0.0.0
QUARKUS_GRPC_SERVER_HOST=0.0.0.0
```
