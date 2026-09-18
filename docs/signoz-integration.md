# SigNoz alongside Grafana: the same telemetry, a genuinely different tool

Everything in this project up to now has assumed one destination for
telemetry: Tempo, Loki, and Prometheus behind Grafana, fed by one
otel-collector. That's a completely legitimate stack, and it stays the
primary one here. But it's also worth seeing what changes when the same OTLP
data goes to a platform that was built from day one to be a single
observability product rather than three specialized ones wired together —
which is exactly what SigNoz is. This document covers what got wired up,
what SigNoz actually is, how it differs from the Grafana-plus-backends
approach, and what the community consensus looks like on choosing between
the two shapes.

## What's running now

[`otel-collector-config.yaml`](../otel-collector-config.yaml) still exports
traces, logs, and metrics to Tempo/Loki/Prometheus exactly as before. It now
also exports all three, unchanged, to a second destination — SigNoz's own
OTLP receiver — via one added exporter (`otlp_grpc/signoz`) referenced from
all three pipelines. Nothing about how the app is instrumented changed at
all; this is purely a collector-side decision to send a duplicate copy of
everything somewhere else.

SigNoz itself runs as a single container in
[`docker-compose.yml`](../docker-compose.yml), the
`signoz/signoz-standalone` image, pinned to a specific tag and content
digest the same way the rest of this project's stack is. It's worth being
precise about what "single container" actually means here, because it
isn't a smaller deployment — it's the same deployment, repackaged.

### What's actually inside the one container

An earlier pass at this integration ran SigNoz the way its own official
Foundry-generated deployment does: five separate containers — ClickHouse,
ClickHouse Keeper (ZooKeeper's replacement as ClickHouse's coordination
store), Postgres for SigNoz's own app metadata, SigNoz's OTLP-ingesting
otel-collector fork, and the unified UI/query binary — each independently
visible in `docker compose ps`, restartable on its own, and never needing
anything more privileged than a normal container.

Opening up `signoz-standalone` shows the exact same five processes,
confirmed by reading its internal systemd unit files
(`signoz-ingester.service`, `signoz-signoz.service`, two ClickHouse-related
units, and a migration job) — just managed by systemd running *inside* this
one container instead of by docker-compose running five containers. That's
why `privileged: true` is non-negotiable here: it failed to boot at all
without it during testing, because systemd needs that level of host access
to manage processes the way it normally would on a real machine. The
resource footprint underneath is identical either way; what changes is
whether you see one `docker ps` row or five, and whether the pieces can be
restarted independently.

One genuine simplification did come with the swap: this image uses SQLite
for SigNoz's own metadata instead of Postgres, which is a real reduction
from six moving parts (five persistent services plus a migration job) to
five, not just a visual one. The other trade-offs run the other way. This
build tracks an older SigNoz release (`v0.117.1`) than the multi-container
Foundry-generated setup would give you (`v0.142.1` at the time both were
checked) — it's a separately maintained build that isn't kept in lockstep
with SigNoz's main release train. And because systemd inside the container
owns its own hardcoded environment for each service (visible directly in
its unit files), there's no way to pass configuration into it through
Docker's normal `environment:` mechanism — confirmed by setting
`SIGNOZ_TOKENIZER_JWT_SECRET` at the container level and finding it never
reached the process, which still logs a security warning about the missing
secret with no way to quiet it from outside. For a local demo that warning
is cosmetic; it would matter more anywhere the container needs to be
configured after the fact.

None of the plumbing here — image names, ports, the fact that `--privileged`
is genuinely required rather than a copy-paste habit — came from guessing.
The multi-container comparison came from SigNoz's own Foundry deployment
generator; the internals of the standalone image came from actually
starting it and reading its systemd units and logs directly, the same way
everything else in this project gets verified before being written down.

## The one manual step: creating the first account

The one piece that isn't just "start the container and it works" is that
SigNoz refuses to hand its own otel-collector fork a working configuration
until an organization exists — true of both the multi-container and
standalone forms, since they're running the same code. This showed up
directly during verification: the bundled ingester's health-check extension
came up immediately and logged "Everything is ready," but its actual OTLP
receiver never bound to a port, because SigNoz's otel-collector fork runs in
a managed mode where the real pipeline configuration is pushed to it over a
protocol called OpAMP by the SigNoz backend — and the backend was refusing
that handshake with `cannot create agent without orgId`. The fix is a single
API call, `POST /api/v1/register`, creating the first admin account and its
organization. The moment that account exists, the ingester's OpAMP client
reconnects, receives real pipeline configuration, and its OTLP receiver
comes up within seconds. This is a one-time bootstrap step for a fresh
SigNoz deployment, not something you'd hit again once an account exists —
but it's the kind of dependency that's invisible from the compose file
alone, which is exactly why it's worth writing down.

## What's actually different from Grafana plus separate backends

The two setups look similar from the outside — both take OTLP in, both
show traces/logs/metrics in a browser — but they're built on genuinely
different premises.

**Storage.** Tempo, Loki, and Prometheus are each a purpose-built engine
for one signal type, optimized around that signal's specific access
patterns. SigNoz stores all three signals in ClickHouse, a general-purpose
columnar analytical database. That's a real trade-off, not a strictly worse
one: a single storage engine means one thing to operate, back up, and scale
instead of three, and ClickHouse is a genuinely fast columnar store for the
kind of aggregation queries observability dashboards run. But it also means
SigNoz's performance characteristics are those of a general OLAP database
tuned for this workload, rather than three engines each built around one
signal's shape from scratch.

**Correlation model.** This is the one that showed up concretely during
verification, not just in theory. Querying SigNoz's ClickHouse schema
directly (`signoz_metrics.samples_v4`) turned up no `trace_id`/`span_id`
columns anywhere, and a search of SigNoz's own source repository for
"exemplar" returned zero results. SigNoz does not implement OTel exemplars
at all — the mechanism this project's [exemplars.md](exemplars.md)
document covers, where Prometheus stores a trace pointer directly on a
metric data point and Grafana draws it as a diamond you can click through
to the exact trace that produced that value. SigNoz's answer to
"metrics-to-traces" is architecturally different: it runs its own
span-metrics processor that derives RED metrics (rate, errors, duration)
directly from spans as they arrive, and its UI lets you pivot from a
service's metrics to its recent traces by service name, operation, and time
range rather than through a literal per-point trace reference. Both get you
from a metric to a trace. They are not the same mechanism, and if you're
specifically relying on point-level exemplar precision — "this exact
latency spike, this exact trace" rather than "traces from around when this
spike happened" — that's a Grafana/Prometheus capability this SigNoz setup
doesn't reproduce.

**One product versus assembled pieces.** Grafana, Tempo, Loki, and
Prometheus are four projects from (mostly) one vendor that compose well
together but remain separately versioned, separately configured, and
separately queried (PromQL, LogQL, TraceQL — three query languages for
three signals). SigNoz is one query surface and one data model across all
three signals from the start, which shows up in small but real ways: a
metric queried here keeps its metric name exactly as emitted
(`customer.email.duplicate`), where Prometheus's remote-write path
normalizes it to `customer_email_duplicate_total` on the way in. Neither is
more correct; they're different conventions from different lineages.

**Where each one earns its complexity.** The Grafana-plus-backends shape
rewards you when you want to pick the best tool per signal, or already run
one of these four pieces for something else. The unified-platform shape
rewards you when the operational cost of running three storage engines and
three query languages outweighs the flexibility of choosing among them —
which is a fair trade for a lot of teams, and is a large part of why
SigNoz, Grafana's own newer "Grafana Cloud" convergence, and similar
platforms have gained ground.

## What the observability community actually does with this choice

The rough current state, worth stating plainly rather than diplomatically
dodging: there is no single consensus, but there is a real trend. The
OpenTelemetry project itself has made the collector and OTLP the neutral
wire format specifically so this choice doesn't have to be made once,
globally, and lived with forever — you can point the same instrumented
application at Grafana's stack, SigNoz, Honeycomb, Datadog, or all of them
at once, which is exactly the pattern demonstrated in this project's
otel-collector config now. Teams starting fresh increasingly lean toward
consolidated platforms (SigNoz, Grafana Cloud's managed convergence of its
own stack, or commercial all-in-one vendors) specifically to avoid
operating three-plus storage systems by hand — that operational tax is the
single most commonly cited reason teams move off a hand-assembled
LGTM-style stack once it's running in production rather than a lab. Teams
with existing investment in one piece (often Prometheus, since it's the
de facto standard for Kubernetes-native metrics regardless of what handles
traces and logs) tend to keep it and bolt on the rest rather than migrate
wholesale. And cost-sensitive or compliance-bound teams frequently favor
self-hosted open-source options in both categories — which is what makes
SigNoz specifically relevant here: it's fully open source and self-hostable
the same way this project's Grafana stack is, not a hosted-only product.

## How to use it

The SigNoz UI is at `http://localhost:8081` (remapped from its default 8080
since this app already owns that port on the host). Log in with:

- **Email:** `user@company.com`
- **Password:** `SignozDemo123!`

These are demo-only credentials for a local, non-production account with no
real data of consequence behind it, kept in plain text here deliberately so
there's nothing extra to remember. The browser session survives container
restarts (verified directly - a token issued before a restart still worked
afterward), so this login is a one-time thing in normal use, not something
you'll be asked for repeatedly.

A concrete way to see both agree: query `customer.repository.count`
(SigNoz) and `customer_repository_count{job="opentelemetry-spring-boot-study"}`
(Prometheus/Grafana) after a load test. During verification both reported
`1923` — the same live database row count, computed independently by two
completely different storage and query engines, from two copies of the
same OTLP stream. That agreement is the actual proof this integration
works, not just that both UIs load.

## Best practices

Treat a platform's stated OTLP support as a starting claim to verify, not a
guarantee that every OTel feature makes the trip. Exemplars are the concrete
example here: the OTLP payload this app emits carries them (they show up
correctly on the Prometheus/Grafana side), but SigNoz's ingest path doesn't
persist them. The only way to know that was checking the actual storage
schema and the vendor's own source, not reading a feature comparison page.

Keep the collector as the integration point, not the application. Every
byte SigNoz receives here comes from the same otel-collector this project's
Grafana stack uses, via one added exporter. The app has no idea a second
backend exists. That's the entire value of standardizing on OTLP in the
first place — swapping or adding a backend is a collector config change,
never an application redeploy.

Pin everything, including a "latest"-tagged image. `signoz/signoz-standalone:latest`
resolved to a real, dated version tag at pull time (`v0.0.4`) — that
resolved tag and digest are what's actually pinned in the compose file, not
the floating `latest` tag itself.

Weigh `privileged: true` deliberately, not reflexively. It's a real
capability grant to the container, not a formality — confirmed here by the
container failing to boot at all without it, since it needs to run systemd
internally. Copying a compose snippet that includes it is fine once you
know why it's there; copying it without checking is how a lab convenience
turns into a habit you regret somewhere it matters more.

## Antipatterns

Don't run two full observability backends in production the way this
project runs them side by side in a lab. Duplicate export to two platforms
is a reasonable way to evaluate one against the other with real traffic,
which is exactly what's happening here — it is not a reasonable steady
-state architecture, since it means paying twice for storage and ingest for
data nobody's actually looking at twice.

Don't assume a single "OTLP-native" backend is interchangeable with
another because both speak the same wire protocol. OTLP standardizes the
transport and the data model's shape; it does not standardize what a given
backend chooses to do with every field in that model once it arrives. This
project's own exemplar findings are the proof.

Don't skip verifying against the platform's actual storage or source when
a claim matters. The `cannot create agent without orgId` failure and the
missing exemplar columns were both found by checking real logs and a real
schema, not by reading what SigNoz's marketing page says it does. That
gap between "should work" and "does work" is exactly where the real
learning happened in this integration, and it's the same discipline this
project's other documents (particularly
[otel-collector-processors.md](otel-collector-processors.md)) have applied
throughout.
