# codex-usage-dashboard

**English** | [日本語](README_ja.md)

`codex-usage-dashboard` is a local, single-binary dashboard for Codex usage,
credit and cost estimates, and attribution.

It receives Codex OTLP logs, enriches them with local Codex metadata, polls the
current Codex usage window, and visualizes them in a dashboard.

It can also normalize Claude Code OTLP `api_request` logs into token and USD
cost rows. Codex and Claude Code are shown on separate dashboard tabs; Codex USD
cost is estimated from credits at 1000 credits = $40, while Claude Code cost is
calculated from its token fields using the Claude API pricing table.

## Requirements

- **Codex CLI**, installed and signed in
  - The dashboard still runs without it (credits and events come from OTLP), but
    the usage % gauges stay empty
- Codex set up to export OTLP (see [Configure Codex OTLP](#configure-codex-otlp))

## Quick Start

The prebuilt binary is **macOS only** (Apple Silicon). Download it from the
[Releases](../../releases) page, then run it:

```sh
unzip codex-usage-dashboard-macos-arm64.zip
chmod +x codex-usage-dashboard
./codex-usage-dashboard
```

The release binary is not currently signed or notarized. If macOS blocks it with
"Apple could not verify" and you trust the downloaded file, remove the quarantine
attribute and run it again:

```sh
xattr -d com.apple.quarantine codex-usage-dashboard
./codex-usage-dashboard
```

On another platform, or want to build it yourself? Build from source — see
[`dev_docs/development.md`](dev_docs/development.md).

Open:

```text
http://127.0.0.1:4318/
```

Use the top-bar tabs to switch between Codex and Claude Code. The range selector
supports relative windows, the current Codex 5h usage window, and a custom
from/to range.

Most charts and usage tables have a local **Cost / Tokens** toggle. The toggle is
per panel, so you can compare one breakdown by USD cost while keeping another in
raw token counts.

By default the app listens only on localhost:

- OTLP gRPC: `127.0.0.1:4317`
- HTTP dashboard and OTLP/HTTP protobuf: `127.0.0.1:4318`

The local database is created at:

```text
data/codex-usage-dashboard.sqlite
```

relative to the directory where you run the binary.

## Configure Codex OTLP

The basic setup points Codex's OTLP log export directly at this dashboard's gRPC
receiver. Edit `~/.codex/config.toml`:

```toml
[otel]
exporter = { otlp-grpc = { endpoint = "http://127.0.0.1:4317" } }
```

Restart Codex after changing the config. New Codex activity should start filling
the dashboard within about a minute.

Keep this dashboard process running while you use Codex or Claude Code to capture
new OTLP events. Events emitted while it is stopped are not backfilled.

### Already exporting Codex OTLP elsewhere?

This dashboard listens on the standard OTLP ports (gRPC `:4317`, HTTP `:4318`) so
Codex can point at it directly. If you already run an
[OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) on those ports,
keep Codex pointed at the collector, fan out a copy to this dashboard, and move
the dashboard off the standard ports.

Quarkus reads a `.env` file from the working directory, so put the overrides
there:

```sh
# .env
QUARKUS_GRPC_SERVER_PORT=14317
QUARKUS_HTTP_PORT=14318
```

Then have the collector send OTLP/HTTP protobuf to `http://127.0.0.1:14318/v1/logs`
(or gRPC to `:14317`).

## Configure Claude Code OTLP

Claude Code support is optional. It uses Claude Code's OTLP log/events stream,
not Codex's local SQLite enrichment path. Start this dashboard, then run Claude
Code with its OTLP logs exporter pointed at the dashboard:

```sh
export CLAUDE_CODE_ENABLE_TELEMETRY=1
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4317
claude
```

The dashboard normalizes Claude Code `api_request` log records that include
token fields. It calculates USD cost by token type at ingest time; if the log
also includes `cost_usd`, that reported value is preserved separately for
comparison. Metrics and traces may be accepted by the receiver, but they are not
stored for dashboard charts.

For the full Claude Code telemetry configuration surface, see the
[Claude Code Monitoring docs](https://code.claude.com/docs/en/monitoring-usage).

## LAN Access

The dashboard is local-only by default. It has **no authentication**, and the
raw-log drill-down (`/api/events/{id}/raw`) returns verbatim OTLP records that can
include working directories, conversation ids, host names, and account metadata.
Exposing it on your LAN gives **every device on the network unauthenticated read
access to that data** — only do this on a trusted network.

To intentionally expose it on your LAN:

```sh
QUARKUS_HTTP_HOST=0.0.0.0 \
QUARKUS_GRPC_SERVER_HOST=0.0.0.0 \
./codex-usage-dashboard
```

Then open `http://<machine-ip>:4318/` from another device.

## Configuration

Quarkus reads a `.env` file from the working directory. See
[`.env.example`](.env.example) for copyable defaults. Useful environment
variables:

```sh
# Ports / bind addresses
QUARKUS_HTTP_HOST=127.0.0.1
QUARKUS_HTTP_PORT=4318
QUARKUS_GRPC_SERVER_HOST=127.0.0.1
QUARKUS_GRPC_SERVER_PORT=4317

# Local storage
QUARKUS_DATASOURCE_JDBC_URL='jdbc:sqlite:data/codex-usage-dashboard.sqlite?journal_mode=WAL&busy_timeout=10000'

# Codex local state used for enrichment
CODEX_STATE5_PATH="$HOME/.codex/state_5.sqlite"
CODEX_LOGS2_PATH="$HOME/.codex/logs_2.sqlite"
CODEX_BIN=codex

# Local telemetry retention
CODEX_USAGE_DASHBOARD_RETENTION_EVERY=1h
CODEX_USAGE_DASHBOARD_RETENTION_OTEL_LOG_RECORDS=14d
CODEX_USAGE_DASHBOARD_RETENTION_ANNOTATED_EVENTS=365d
CODEX_USAGE_DASHBOARD_RETENTION_USAGE_SAMPLES=365d
```

Retention is enforced independently for the tables owned by this app:

- `otel_log_records`: raw OTLP log records. These are the largest rows and back
  the raw event drill-down. Old raw rows are deleted only after the annotation
  cursor has passed them, so unprocessed backlog is preserved.
- `annotated_events`: parsed Codex and Claude Code token, cost, trigger, and
  error rows used by the dashboard charts and tables.
- `usage_samples`: periodic Codex rate-limit percentage snapshots.

Set any retention value to `0` or `disabled` to keep that table indefinitely.
Deleting raw OTLP rows does not remove already annotated chart history, but raw
drill-down and annotation replay are unavailable for rows whose raw source was
deleted. SQLite may reuse freed pages without immediately shrinking the database
file; run `VACUUM` manually if you need to compact the file on disk.

## OTLP Support

Supported:

- OTLP/gRPC on `:4317`
- OTLP/HTTP protobuf on `:4318/v1/logs`
- gzip-compressed OTLP/HTTP protobuf bodies
- Claude Code OTLP log `api_request` records with token and `cost_usd` fields

Not supported:

- OTLP/HTTP JSON (`Content-Type: application/json`)

OTLP/HTTP JSON requests return `415 Unsupported Media Type` so misconfigured
exporters do not appear to succeed while silently dropping data. Make sure any
OTLP/HTTP exporter sends protobuf (`encoding: proto`), not JSON.

## Development Docs

Developer-facing notes live in [`dev_docs/`](dev_docs/). Start with:

- [`dev_docs/architecture.md`](dev_docs/architecture.md)
- [`dev_docs/development.md`](dev_docs/development.md)
- [`dev_docs/token-accounting.md`](dev_docs/token-accounting.md)

## License

Licensed under the [Apache License, Version 2.0](LICENSE). See [`NOTICE`](NOTICE)
for attribution of bundled third-party components (Apache ECharts).
