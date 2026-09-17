# Custom metrics: all seven OTel instrument kinds, honestly mapped

OpenTelemetry defines seven kinds of metric instrument: Counter, Asynchronous
Counter, Gauge, Asynchronous Gauge, UpDownCounter, Asynchronous UpDownCounter, and
Histogram. This app implements one example of each. The interesting part isn't the
list — it's that Micrometer, which is what everything in this project's
instrumentation goes through (see
[observability-libraries.md](observability-libraries.md)), doesn't have seven
matching meter types. It has five: `Counter`, `FunctionCounter`, `Gauge`,
`DistributionSummary`, and `Timer`. Getting honest coverage of all seven OTel kinds
without reaching for the raw OTel API (which would mean a second, parallel
instrumentation pathway — exactly what we've argued against elsewhere in this
project) meant working out which Micrometer construct actually represents each OTel
concept, rather than assuming a 1:1 mapping exists.

All seven live in one place,
[`CustomerMetrics`](../src/main/java/dev/vivekanand/opentelemetryspringbootstudy/customer/metrics/CustomerMetrics.java) —
registered once in its constructor rather than scattered as raw `MeterRegistry`
calls across the classes that trigger them, which is the standard advice for
keeping metric names and tags from drifting apart over time.

## The seven, and what each actually is

**Counter** — `customer.email.duplicate`. A plain monotonic tally, incremented
inline the moment a create or update request is rejected for a duplicate email.
This is the simple case: the counter has no purpose other than being observed, so
incrementing it *is* the telemetry action.

**Asynchronous Counter** — `customer.validation.errors`, via `FunctionCounter`.
This is genuinely different from a plain `Counter`, not just a renamed one:
`FunctionCounter` observes a monotonic value your application already tracks for
its own reasons, rather than being told "increment now." Here that value is an
`AtomicLong` bumped once per rejected validation request; the meter just reads it.

**Gauge** — `customer.recent.buffer.size`. Micrometer's `Gauge` is inherently
poll-based — there's no separate "push a value" primitive the way OTel's newer
synchronous gauge has. So the distinction between this and the next one is about
*what backs the value*, not the mechanism. Here it's a bounded in-memory deque of
the last 50 created customer ids, maintained by application code as a side effect
of `create()`. The gauge just reports its current size whenever read.

**Asynchronous Gauge** — `customer.repository.count`. Same `Gauge` API, but backed
by `CustomerRepository::count` directly — a live database query, run fresh every
single time the value is observed, existing purely to be measured. Nothing in the
app maintains this number; it has no side effects to piggyback on.

**UpDownCounter** — `customer.mutations.inflight`. Micrometer has no dedicated type
for this at all — confirmed by listing every class in
`io.micrometer.core.instrument`, there's no `UpDownCounter`. The standard,
well-documented idiom is a `Gauge` over an `AtomicInteger` the application
increments and decrements directly. Here it brackets every create/update/delete
call — incremented before the call, decremented in a `finally` after, so it stays
accurate whether the call succeeds or throws.

**Asynchronous UpDownCounter** — `customer.net.created`. Same Gauge-over-atomic
idiom, but instead of one counter bumped up and down directly, it's *derived*: two
independent tallies (`createdCount`, `deletedCount`, both `AtomicLong`) combined
fresh on every read. It answers "how many more customers exist now than at process
start," net of both directions.

**Histogram** — `customer.validation.violations`, via `DistributionSummary`.
Deliberately not a `Timer` — this project already has plenty of duration histograms
from the custom spans (see [custom-spans.md](custom-spans.md)), so a duration
histogram here would be redundant. This one records a genuinely different
distribution: how many fields fail validation per rejected request, letting you see
whether client-side bugs tend to trip one field or several at once.

## Where each gets triggered

- `customer.email.duplicate` — `CustomerServiceImpl.create()` and `.update()`, at
  the point `DuplicateEmailException` is thrown
- `customer.validation.errors` / `customer.validation.violations` —
  `GlobalExceptionHandler.handleValidation()`, alongside the existing structured log
- `customer.recent.buffer.size` / `customer.net.created` (the created side) —
  `CustomerServiceImpl.create()`, on success only
- `customer.net.created` (the deleted side) — `CustomerServiceImpl.delete()`, on
  success only
- `customer.repository.count` — nowhere; it's computed on demand by Prometheus/the
  collector reading the gauge, not pushed by any code path
- `customer.mutations.inflight` — bracketed around the entire body of `create()`,
  `update()`, and `delete()`

## Finding them in Grafana

Same route as any Prometheus metric here: **Explore** → **Prometheus** datasource,
then query by name. A few practical notes specific to these:

- The `Counter` and `FunctionCounter` both export with a `_total` suffix
  (`customer_email_duplicate_total`, `customer_validation_errors_total`) — that's
  Prometheus/OTel naming convention for counters, not something we added.
- The `Histogram` exports as four series, same shape as any other histogram in this
  stack: `customer_validation_violations_bucket`, `_count`, `_sum`, and `_max`.
- The four `Gauge`-backed ones (`customer_recent_buffer_size`,
  `customer_repository_count`, `customer_mutations_inflight`,
  `customer_net_created`) export with no suffix at all.
- **Filter by `job="opentelemetry-spring-boot-study"`.** We hit this directly while
  verifying: running the test suite pushes metrics from real `@SpringBootTest`
  contexts too (Micrometer's OTLP registry has its own built-in default endpoint,
  independent of test config — a quirk from earlier in this project), landing under
  `job="unknown_service"` in the same Prometheus. An unfiltered query can silently
  return that leftover series instead of the real app's. Always add the job label
  when you want the live app's numbers specifically.
- **A metric with no recent pushes goes stale after ~5 minutes**, Prometheus's
  default lookback window. If the app isn't currently running, a plain "now" query
  in Explore can come back empty even though the metric worked fine and has a full
  history — that's expected staleness handling, not lost data. The history is still
  there; query a time range, or an explicit past timestamp, to see it.

## Verified working

Queried through **Grafana's own datasource proxy**
(`/api/datasources/proxy/uid/prometheus/...`), not just Prometheus directly, after a
60-second load test (~4,800 requests, mixed valid/invalid/duplicate traffic),
re-confirmed stable across two checks a full push interval apart:

| Metric | Value | Sanity check |
|---|---|---|
| `customer_email_duplicate_total` | 141 | real duplicate-email conflicts from the run |
| `customer_validation_errors_total` | 212 | — |
| `customer_validation_violations_count` | 212 | matches the counter above exactly, as designed |
| `customer_validation_violations_sum` | 848 | = 212 × 4 — every invalid payload in the load test trips exactly 4 fields |
| `customer_recent_buffer_size` | 50 | capped at its configured limit, confirmed under real concurrent load, not just in a unit test |
| `customer_repository_count` | 482 | live row count, queried fresh |
| `customer_net_created` | 482 | **identical** to the row count above — two completely independently-computed numbers (one an app-maintained tally, one a live DB query) agreeing is strong evidence both are actually correct |
| `customer_mutations.inflight` | 0 | back to zero once the load test finished, as expected |

One thing worth knowing if you reproduce this yourself: Micrometer's OTLP metrics
push runs on a 60-second timer, so a value checked moments after a load test
finishes can be a mid-flight snapshot rather than the true final total — we caught
exactly this the first time (values kept climbing on a second check that should
have been static) and re-verified by waiting past a full push cycle and confirming
the number held steady before trusting it.
