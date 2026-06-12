# Development

## Requirements

- JDK 25 or newer. A JVM build needs any JDK 25+; the native build needs a
  GraalVM 25 distribution with `native-image` available.
- Maven wrapper (`./mvnw`) from this repository
- Codex CLI installed and authenticated for live usage polling

The Maven wrapper downloads Maven 3.9.12.

## Build

Compile:

```sh
./mvnw -q -DskipTests compile
```

Run tests:

```sh
./mvnw -q test
```

There is no automated test suite yet, so this currently runs no tests.

Run in Quarkus dev mode:

```sh
./mvnw quarkus:dev
```

Package JVM artifact:

```sh
./mvnw -DskipTests package
```

The JVM package is written under `target/quarkus-app/` and starts via
`target/quarkus-app/quarkus-run.jar`.

Package native binary:

```sh
./mvnw -DskipTests -Dnative package
```

The native build requires a GraalVM 25 distribution with `native-image` available
(set `GRAALVM_HOME` / `JAVA_HOME` to it, or put `native-image` on `PATH`).
Alternatively build inside a container with
`-Dquarkus.native.container-build=true` (needs a container runtime). The GitHub
release workflow builds the published binaries this way.

`application.properties` sets:

```text
quarkus.package.output-name=codex-usage-dashboard
quarkus.package.jar.add-runner-suffix=false
```

so the intended release artifact name is `target/codex-usage-dashboard`.

## Local Verification

Health:

```sh
curl -s http://127.0.0.1:4318/health
```

Dashboard:

```sh
curl -s -o /tmp/codex-usage-dashboard.html -w '%{http_code}\n' \
  'http://127.0.0.1:4318/?range=6h&grain=5m'
```

Core API:

```sh
curl -s 'http://127.0.0.1:4318/api/summary?range=6h'
curl -s 'http://127.0.0.1:4318/api/summary?source=claude&range=6h'
curl -s 'http://127.0.0.1:4318/api/credits/by-type-timeseries?range=6h&grain=5m'
curl -s 'http://127.0.0.1:4318/api/cost/by-type-timeseries?source=codex&range=6h&grain=5m'
curl -s 'http://127.0.0.1:4318/api/tokens/by-type-timeseries?source=claude&range=6h&grain=5m'
curl -s 'http://127.0.0.1:4318/api/cost/by-model?source=claude&range=6h'
curl -s 'http://127.0.0.1:4318/api/tokens/by-model?source=claude&range=6h'
curl -s 'http://127.0.0.1:4318/api/tokens/by-trigger-timeseries?source=codex&range=6h&grain=5m'
curl -s 'http://127.0.0.1:4318/api/tokens/by-model-trigger?source=claude&range=6h'
curl -s 'http://127.0.0.1:4318/api/events/conversations?source=codex&limit=5&range=6h'
curl -s 'http://127.0.0.1:4318/api/events/completions?limit=5&range=6h'
curl -s 'http://127.0.0.1:4318/api/events/errors?limit=5&range=6h'
```

OTLP/HTTP JSON should fail explicitly:

```sh
curl -s -X POST \
  -H 'Content-Type: application/json' \
  -d '{}' \
  -w '\n%{http_code}\n' \
  http://127.0.0.1:4318/v1/logs
```

Expected status: `415`.

## OTLP Notes

Codex can send OTLP directly to the gRPC receiver:

```toml
[otel]
exporter = { otlp-grpc = { endpoint = "http://127.0.0.1:4317" } }
```

An OpenTelemetry Collector can fan out to this app with an OTLP/HTTP protobuf
exporter:

```yaml
exporters:
  otlphttp/codex_usage_dashboard:
    endpoint: http://127.0.0.1:4318
```

The exporter appends `/v1/logs` automatically. gzip is supported.

Claude Code can send OTLP logs directly to the same receiver:

```sh
export CLAUDE_CODE_ENABLE_TELEMETRY=1
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4317
claude
```

Only Claude Code log/events are used by this dashboard. Metrics and traces are
accepted by the receiver but discarded.

## Database Replay

Raw records are the short-term source of truth. To replay derived rows after
changing annotation logic:

```sql
DELETE FROM annotated_events;
UPDATE cursor SET value = '0' WHERE name = 'annotate_log_id';
```

Then let the annotate job run again.

Per-row annotate exceptions stop the pass before the failing row, so parser or
DB bugs remain replayable after the fix is deployed.

Retention can remove old rows from `otel_log_records` after the annotate cursor
has passed them. Derived chart history can remain in `annotated_events`, but
rows whose raw source was deleted cannot be replayed and `/api/events/{id}/raw`
will return 404.

## Release Hygiene

Before creating a public repository or release:

- Do not commit `data/`.
- Do not commit `target/`.
- Do not commit local logs or `.env` files.
- Verify the release binary starts with localhost-only defaults.
- Verify `README.md` still describes binary usage rather than development setup.
