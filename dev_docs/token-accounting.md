# Token And Credit Accounting

This document captures the Java app's token and credit accounting model.

## Primary Signal

Codex emits token-bearing OTLP log records on completion events:

```text
attributes.event.name = codex.sse_event
attributes.event.kind = response.completed
attributes.input_token_count
attributes.cached_token_count
attributes.output_token_count
attributes.reasoning_token_count
attributes.tool_token_count
attributes.conversation.id
attributes.model
attributes.originator
resource_attributes.host.name
```

The Java receiver stores every OTLP log record raw, but `AnnotateJob` only keeps
records with token counts or `error.message` in `annotated_events`.

See [`codex-data-model.md`](codex-data-model.md) for the observed OTLP and
Codex SQLite fields, including which IDs are not present on completion rows.

Claude Code emits token-bearing OTLP log records as `api_request` events. The
dashboard stores them in the same `annotated_events` table with
`source_tool = 'claude'`:

```text
body = api_request
resource_attributes.service.name = claude-code / claude-code-desktop
attributes.request_id
attributes.session.id
attributes.model
attributes.input_tokens
attributes.cache_creation_tokens
attributes.cache_read_tokens
attributes.output_tokens
attributes.cost_usd
attributes.prompt.id
attributes.query_source
attributes.agent.name
```

These fields follow the
[Claude Code Monitoring API request event](https://code.claude.com/docs/en/monitoring-usage).

Claude `request_id` is used for dedupe. `session.id` is stored as `thread_id`.
`cache_creation_tokens + cache_read_tokens` is stored in
`cached_input_token_count` for display and conversation totals. Claude
`reported_cost_usd` preserves the log-provided value when present. The dashboard
calculates and stores Claude `cost_usd` plus per-type USD columns at annotation
time. `total_credits` remains null for Claude rows because Codex credits are a
separate unit.

Claude Code cache writes are treated as 5-minute cache writes. Cost components
are:

```text
input_cost_usd          = input_tokens          * base_input_rate       / 1_000_000
cache_creation_cost_usd = cache_creation_tokens * 5m_cache_write_rate   / 1_000_000
cache_read_cost_usd     = cache_read_tokens     * cache_hit_rate        / 1_000_000
output_cost_usd         = output_tokens         * output_rate           / 1_000_000
cost_usd                = sum(component costs)
```

The live table is `credit/ClaudeRateCard.java`.

Source of truth:

```text
https://platform.claude.com/docs/ja/about-claude/pricing
```

## Credit Formula

`input_token_count` is the full input count. Cached input is a subset of it.

```text
uncached_input = max(0, input_token_count - cached_token_count)

input_credits  = uncached_input     * input_rate  / 1_000_000
cached_credits = cached_token_count * cached_rate / 1_000_000
output_credits = output_token_count * output_rate / 1_000_000
total_credits  = input_credits + cached_credits + output_credits
cost_usd       = total_credits * 0.04
```

`reasoning_token_count` is recorded by Codex but is not billed separately here;
it is a subset of output tokens.

Codex USD cost is an estimate derived from credits at 1000 credits = $40.
Existing Codex rows are backfilled into `cost_usd` during schema initialization
when `total_credits` is present and `cost_usd` is still null.

## Dashboard Token Mode

Dashboard panels that expose a `Cost / Tokens` toggle use token mode as the sum
of the additive token categories shown in the by-type chart:

```text
Codex tokens  = uncached_input + cached_input + output
Claude tokens = input_tokens + cache_creation_tokens + cache_read_tokens + output_tokens
```

Token-mode model, trigger, model x trigger, and trigger-over-time endpoints all
use the same source-specific token expression. Cost mode uses the stored
`cost_usd` and per-type USD component columns instead.

## Rate Card

The live table is `credit/RateCard.java`.

As of 2026-06-09:

| model | input | cached input | output |
|---|---:|---:|---:|
| gpt-5.5 | 125 | 12.5 | 750 |
| gpt-5.4 | 62.5 | 6.25 | 375 |
| gpt-5.4-mini | 18.75 | 1.875 | 113 |
| gpt-5.3-codex | 43.75 | 4.375 | 350 |
| gpt-5.2 | 43.75 | 4.375 | 350 |

Unknown models fall back to `gpt-5.3-codex` rates.

Source of truth:

```text
https://developers.openai.com/codex/pricing
```

## Fast / Priority Tier

Codex OTLP does not export the service tier. `logs_2.sqlite` request/handler
frames can carry values such as:

```text
service_tier: Some("priority")
service_tier: Some(Some("priority"))
```

The Java app does not estimate `service_tier` for annotated rows. `AnnotateJob`
stores `service_tier = NULL` and computes credits at the standard rate, and
`RateCard` carries no Fast multiplier. For reference, OpenAI's Codex speed docs
put the Fast/priority surcharge at 2.5× for GPT-5.5 and 2× for GPT-5.4 (the only
two models Fast supports); a future implementation would reintroduce that once a
reliable per-turn tier source exists.

Important precision boundary: reliable service-tier values are turn-level
request-config signals in `logs_2`, while token-bearing OTLP completion rows do
not carry `turn_id` or `submission_id`. A broad thread/time join has low
coverage, and the latest tier seen in a thread can be wrong for mixed-tier
threads. Prefer the confidence model in
[`codex-data-model.md`](codex-data-model.md) before using tier for a Fast
surcharge. Until that is implemented, keep Fast surcharge out of credit totals.

Source of truth:

```text
https://developers.openai.com/codex/speed
```

## Trigger Classification

Each annotated row gets a `trigger`:

- `user`: direct user request. For Codex this means `conversation.id` exists in
  `state_5.threads`; for Claude this is the non-agent default.
- `user_driven_agent`: Claude Code helper work tied to a user action, such as
  `generate_session_title`, or an agent `api_request` whose `prompt.id` has a
  matching `user_prompt` raw event.
- `agent`: Claude Code agent work without a matching `user_prompt` for the same
  `prompt.id`.
- `ambient`: Codex turn is not a known user thread, and `logs_2` contains
  ambient suggestion signatures.
- `memory`: Codex turn is not a known user thread, and `logs_2` contains memory
  signatures.
- `background`: no Codex thread id, no local match, or lookup unavailable.

Current signatures:

```text
ambient suggestions
"suggestions":[
\"suggestions\":

/memories/
MEMORY.md
memory_summary.md
```

This classification is best-effort because Codex local DB schemas are private
implementation details.

Compared with service-tier attribution, trigger classification has a stronger
shape: `user` is a direct `conversation.id` to `state_5.threads.id` join, while
`ambient` and `memory` are thread-level signatures. Service tier is per turn and
is not exported on the completion row, so it generally has lower recall than
trigger classification.

## Usage Percent

Usage percent is not stored in Codex SQLite. The usage job polls:

```text
codex app-server --listen stdio://
account/rateLimits/read
```

and appends returned windows to `usage_samples`.

Usage percent is account-wide and sampled at poll time. It is useful for local
correlation, not a replacement for official billing/accounting.

## Error Rows

Rows with `error.message` are stored in `annotated_events` even when they have no
token counts. They appear in the dashboard's Recent errors table. They are not
included in credit totals because `total_credits` is null.
