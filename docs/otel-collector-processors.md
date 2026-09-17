# Collector processors: what they do and what they actually did here

Everything so far in this project's telemetry pipeline has treated the
otel-collector as a plain relay: receive OTLP on one side, batch it, forward it
to Tempo, Loki, and Prometheus on the other. That's a legitimate way to run a
collector, but it undersells what the "collector" part of the name is for.
Processors are the stage in between receiving and exporting where you get to
reshape, enrich, filter, or protect the collector and the telemetry passing
through it — without touching a single line of application code. This is
what makes the collector a genuine architectural layer rather than just a
network hop.

[`otel-collector-config.yaml`](../otel-collector-config.yaml) now runs six
processors across the three pipelines. The rest of this document walks
through why each one is there, and what actually happened when real load-test
traffic went through them — including two things that didn't work the way a
first read of the config would suggest.

## Why processors exist at all

Three recurring problems show up in any collector deployment, and processors
are the answer to all three:

**The collector itself can be a liability.** A collector with no memory
ceiling will happily buffer telemetry until the process gets OOM-killed by
the OS, taking down every pipeline at once rather than degrading one. That's
strictly worse than dropping some data under pressure.

**Data arrives shaped for the producer, not the consumer.** Spans, logs, and
metrics carry whatever attributes the instrumentation library decided to
attach. Some of that is redundant by the time it reaches storage, some of it
is unwanted (verbose framework noise, raw internal detail), and some of it is
outright sensitive.

**Volume and cost don't stay flat.** A service under real production load
generates far more telemetry than anyone will ever look at. Sampling and
filtering decisions made at the collector control what you pay to store and
query, without asking every service team to change their instrumentation.

Processors sit in the pipeline specifically to address these three things,
and the order they run in matters — a processor can only act on what the
one before it left intact.

## The six, in the order they actually run

**`memory_limiter`** goes first in every pipeline, no exception. It watches
the collector's own memory use and starts refusing new data once usage
crosses a threshold, which is the correct failure mode: better to shed load
at the front door than let an unbounded queue take the whole process down.
Here it's configured with `limit_mib: 400` and a `spike_limit_mib: 100`
buffer above that, checked every second. If this ran anywhere but first, data
would already have been copied and partially processed by other stages
before the safety check ever saw it — defeating the point.

**`resource`** stamps a `collector.name: otel-collector-local` attribute
onto whatever passes through — traces, logs, and metrics alike, since
`resource` isn't signal-specific. The practical use case this demonstrates:
tagging telemetry with something about *where it was collected*, independent
of what the *emitting service* set. In a real deployment with more than one
collector instance (per-region, per-cluster, per-environment), this is how
you'd tell, after the fact, which collector a given span or log actually
passed through — useful when debugging the pipeline itself, not the
application.

**`attributes/scrub_url`** is configured to delete an `http.url` attribute
from spans, framed as a demonstration of stripping a high-cardinality or
sensitive field before export. It's schema-valid and running — but see
"What didn't work as billed" below, because in this app it never actually
removes anything.

**`probabilistic_sampler`** is set to `sampling_percentage: 100`, which
changes nothing right now. It's included specifically to show *where* the
sampling knob lives: this is the dial you'd turn down in a busier or
cost-sensitive environment to keep a representative fraction of traces
instead of all of them. At 100% it's a no-op by design, not a bug — the
point here is placement, not effect.

**`filter/drop_framework_noise`** drops log records whose instrumentation
scope name matches Hibernate or HikariCP, using an OTTL `IsMatch` condition
against `instrumentation_scope.name`. This is the one genuinely consequential
filter in the set: Hibernate's startup banter (dialect detection, JTA
platform warnings, connection pool configuration dumps) and HikariCP's own
startup chatter add real volume without adding anything queryable that the
app's own structured logs don't already cover. Dropping them at the
collector means every service that later gets added to this stack benefits
without each one needing its own logging-level tuning.

**`batch: {}`** runs last in every pipeline, same as before this change. It
groups records into fewer, larger export requests instead of one HTTP/gRPC
call per span or log line. Running it last matters for the same reason
`memory_limiter` runs first: everything upstream of `batch` should already
be in its final shape, since batching is purely about export efficiency, not
content.

## What actually happened under load

A 60-second, 20-VU k6 run generated 4,871 requests after the new config
went live (`docker compose restart otel-collector`, config picked up cleanly
from the mounted file, confirmed via `docker compose logs otel-collector`
showing `memorylimiter` initialize with `limit_mib: 400` and the collector
reporting itself ready before the run started).

**`resource` worked exactly as expected, on all three signals.** A trace
pulled from Tempo after the run shows `collector.name: otel-collector-local`
sitting alongside `service.name` and the other SDK resource attributes on
every span's parent resource. The same label shows up as structured metadata
on log lines queried from Loki. And on the metrics side, Prometheus's
synthetic `target_info` series — the one Prometheus's OTLP receiver
generates to carry resource-level attributes that don't belong on any single
metric — carries `collector_name="otel-collector-local"`, joinable against
every `customer_*` series from this job.

**`filter/drop_framework_noise` worked, but verifying it took an extra
step.** The first check was misleading: querying Loki for log lines with
`scope_name` matching `hibernate` or `zaxxer` (HikariCP's package) returned
five hits each, which looked like the filter was silently doing nothing.
Looking at the actual timestamps showed why — those were Hibernate startup
messages ingested *before* the collector was restarted with the new config,
still sitting in Loki because a config change doesn't retroactively delete
already-ingested data. Re-running the same query with a time range starting
strictly after the restart returned zero matches. As a second, cleaner
check, a synthetic OTLP log record with `instrumentation_scope.name:
"org.hibernate.orm.core"` was posted directly to the collector's OTLP HTTP
endpoint — and never appeared in Loki. Meanwhile the app's own logs
(`CustomerServiceImpl`'s "Customer created"/"Customer updated" entries) kept
flowing through the same restart with no gap. The lesson generalizes beyond
this one processor: when you change a filter and go looking for proof, bound
your query by the moment you applied the change, or old data will look like
a bug that isn't there.

**The seven custom metrics and their exemplars came through unaffected.**
Post-load-test values (`customer_email_duplicate_total=274`,
`customer_validation_errors_total=453`,
`customer_validation_violations_count=453`,
`customer_validation_violations_sum=1812` — 1812/453 = 4 exactly, the same
invariant documented in [custom-metrics.md](custom-metrics.md)) queried
correctly both directly against Prometheus and through Grafana's own
datasource proxy. `query_exemplars` against
`customer_validation_violations_bucket` still returned real `trace_id`/
`span_id` pairs, confirming `memory_limiter`, `resource`, and `batch` in the
metrics pipeline don't interfere with exemplar propagation.

## What didn't work as billed — and why that's worth knowing

`attributes/scrub_url` is configured to delete `http.url` from spans. Across
every trace pulled from Tempo during verification, that attribute never
appeared in the first place. This app's HTTP server spans carry `uri`,
`method`, `outcome`, and `status` — that's Micrometer's own server
observation convention, not the OpenTelemetry semantic convention
(`http.url`, `http.method`, `http.status_code`) that an agent-based
auto-instrumentation setup would emit instead. The processor is correctly
written and would work exactly as intended against spans from a
Java-agent-instrumented service, but against this specific app's own
Micrometer-driven spans, it's a no-op — there's nothing under that key to
delete.

That's left in the config deliberately rather than quietly fixed, because
it's a realistic trap: an `attributes` processor rule that looks right,
validates cleanly, and runs without error can still do nothing at all if the
key it targets doesn't match what your actual instrumentation emits. The fix
isn't a config change here so much as knowing to check — which is exactly
what pulling a real trace and reading its actual attribute keys, rather than
trusting the processor's intent, caught.

The more interesting discovery came from that same trace inspection: on a
409 (duplicate email) response, the `customer.create` span carries an
`exception` event whose `exception.message` attribute contains the raw
email address that triggered the conflict — `Customer already exists with
email: someone@example.com`, verbatim. That's a genuine PII leak sitting in
trace data right now, and it's not something `attributes/scrub_url` (which
only touches span-level attributes) or the `filter` processor as configured
(which only runs on the logs pipeline here) would catch. Fixing it properly
would mean either not including the raw email in the exception message in
application code, or reaching for the collector-contrib `redaction`
processor's value-masking against span events — worth doing if this were a
real service, out of scope for what this task set out to demonstrate, and
recorded here rather than silently left for someone to rediscover later.

## Best practices

Order is not cosmetic. `memory_limiter` first and `batch` last are the two
firm rules; everything else is negotiable, but both of these exist
specifically because of what runs before and after them.

Treat every processor's effect as something to verify against real data,
not just something to infer from the config. A `filter` or `attributes`
rule that references the wrong attribute key, the wrong scope name, or the
wrong signal will validate and run without complaint while quietly doing
nothing — the collector has no way to know your intent, only your syntax.

Keep processors signal-scoped in your head even when a processor type
technically isn't. `resource` runs across traces, logs, and metrics here;
`filter` only runs on logs. Don't assume a processor you've proven out on
one pipeline behaves identically wired into another without checking.

Prefer collector-side filtering for volume that's about the collector's own
dependencies (framework startup noise, connection pool chatter) rather than
application-specific logic. It scales to every service that flows through
the collector, not just this one.

## Antipatterns

Don't put `memory_limiter` anywhere but first, and don't skip it because
"the collector has plenty of headroom in dev." It's the one processor whose
job is entirely about what happens when your assumptions about headroom
turn out to be wrong.

Don't treat a processor that validates and starts cleanly as proof it's
doing what you intended. Schema validation confirms the collector understood
your config; it says nothing about whether the attribute key, scope name, or
condition you wrote actually matches real telemetry. `attributes/scrub_url`
in this exact config is the concrete example — it's valid, it's running,
and it does nothing, because the key it targets doesn't exist in this app's
spans.

Don't use the collector's `filter` or `attributes` processors as a
substitute for fixing a real data-hygiene problem in application code when
the better fix is upstream. Redacting a value at the collector after it's
already been serialized into a stack trace, sent over the wire, and
processed by every upstream stage is a weaker guarantee than never having
put it there. The collector is a good place to catch what you can't control
(framework logging, third-party library output); it's a worse place to
paper over what you can.
