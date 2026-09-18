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

SigNoz runs as seven services in [`docker-compose.yml`](../docker-compose.yml),
mirroring the architecture SigNoz's own official installer (a tool called
Foundry) generates today, all images pinned to a specific tag and content
digest the same way the rest of this project's stack is:

- **`signoz-telemetrykeeper-clickhousekeeper-0`** — ClickHouse Keeper,
  which replaced ZooKeeper as ClickHouse's coordination store in current
  releases.
- **`signoz-telemetrystore-clickhouse-0-0`** — the actual telemetry
  database. Every trace, log, and metric SigNoz ingests ends up in
  ClickHouse tables, not a purpose-built time-series or trace store the way
  Tempo and Prometheus are. A short-lived companion container
  (`signoz-telemetrystore-clickhouse-user-scripts`) fetches a small helper
  binary ClickHouse needs for one of SigNoz's own dashboard functions, then
  exits.
- **`signoz-metastore-postgres-0`** — a small Postgres instance holding
  SigNoz's own application state: user accounts, dashboards, alert rules.
  This is metadata about the tool, not telemetry data.
- **`signoz-signoz-0`** — the web UI and query API, a single unified
  binary in current SigNoz releases rather than the separate
  frontend-plus-query-service split of a couple of years ago.
- **`signoz-ingester`** (`signoz/signoz-otel-collector`) — SigNoz's own
  OTLP receiver. This is a fork of the same otel-collector this project
  already uses, built with ClickHouse-writing exporters instead of the
  generic ones. It listens on 5317/5318 on the host (remapped from the
  standard 4317/4318, since this project's main collector already owns
  those ports) and on 4317/4318 inside the Docker network, which is what
  the app-facing collector actually talks to. A one-shot migration job
  (`signoz-telemetrystore-migrator`) sets up ClickHouse's schema before
  this starts.
- **`signoz-bootstrap`** — a small one-shot container, covered in its own
  section below, that solves the single biggest point of friction in
  running SigNoz locally: the account it needs to exist before it'll do
  anything.

## Why this replaced a single all-in-one container

An earlier version of this integration ran SigNoz as one container —
`signoz/signoz-standalone`, which bundled ClickHouse, its coordination
store, and SigNoz's own backend into a single privileged image running
systemd internally to manage all of it. It worked, with real caveats
(documented at the time: an older, separately-maintained SigNoz version,
a JWT secret warning that couldn't be silenced because Docker's
`environment:` never reached the systemd-managed processes inside).

What forced the replacement wasn't any of those caveats — it was hitting
two real failures trying to run that same image on a different machine.
First: SigNoz's registration endpoint refused with `"self-registration is
disabled"`, which turned out to be entirely by design — reading SigNoz's
own source (`pkg/query-service/app/http_handler.go`) showed the endpoint
permanently locks itself the moment any account is created, precisely so
a network-exposed instance can't let a stranger register as the org
owner. That's correct behavior; it just meant the standalone container's
volume on that other machine wasn't actually the clean slate it looked
like. Second, and more serious: the ingester process inside that same
container kept failing to start, its pre-start migration check looping
on `connection refused` against ClickHouse until systemd gave up
entirely — ClickHouse, bundled in that same privileged container, simply
hadn't finished starting in time, something that showed up on one machine
and not the other. Both problems trace back to the same root cause:
running an entire multi-process backend inside one container, coordinated
by systemd instead of by a proper orchestrator, is exactly the kind of
setup where startup ordering and first-boot timing quietly stop being
guaranteed the moment you're not on the exact machine it was built and
tested on.

The multi-container form used now is what SigNoz's own installer produces,
which means startup ordering is expressed the way Docker Compose is
actually built to express it — explicit `depends_on` conditions with real
health checks — rather than implicitly, inside systemd units nothing
outside the container can see or influence.

## The bootstrap step, now automatic

Self-hosted SigNoz always requires an admin account before its ingester
will accept data — not a bug, not something to turn off, just how the
product is designed. Every earlier version of this document walked
through running that registration by hand, and the entire previous
back-and-forth that led to this rewrite was, in the end, about that one
manual step not traveling well between machines.

`signoz-bootstrap` removes the manual step entirely. It's a small
container that runs once, after the SigNoz backend is confirmed healthy,
and does exactly this:

```bash
curl -X POST http://signoz-signoz-0:8080/api/v1/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Demo Admin","orgDisplayName":"otel-study","orgName":"otel-study","email":"user@company.com","password":"SignozDemo123!"}'
```

If that call returns `200`, the org didn't exist yet and now it does. If
it returns `400`, the org already exists — which, given what's now known
about that endpoint's design, is the *expected* outcome on every
`docker compose up` after the first one, not an error condition. The
bootstrap container treats both as success and exits `0`; only a genuinely
unexpected response fails the container, which would in turn block the
ingester from starting, surfacing the problem immediately instead of
silently.

This is the actual fix for the cross-machine login problem: the account
isn't something anyone has to remember to create anymore, on this machine
or any other. Clone the repository, run `docker compose up -d`, and by the
time the stack finishes starting, the org and the admin account already
exist — verified end to end, including on a fresh set of volumes with
nothing pre-existing.

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

Unlike every earlier version of this document, these credentials are
genuinely portable now — not a snapshot of one machine's local state.
`signoz-bootstrap` creates this exact account automatically on every
`docker compose up`, on any machine, from a clean clone or an existing
one. There's nothing to run by hand and nothing that only works where it
was first set up.

A concrete way to see both agree: query `customer.repository.count`
(SigNoz) and `customer_repository_count{job="opentelemetry-spring-boot-study"}`
(Prometheus/Grafana) after a load test. During verification both reported
the exact same number, computed independently by two completely different
storage and query engines, from two copies of the same OTLP stream. That
agreement is the actual proof this integration works, not just that both
UIs load.

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

Automate the parts of a first-run setup that would otherwise be a manual
step someone has to remember. `signoz-bootstrap` is the concrete example:
turning "log in and register an account" from a step in a README into an
idempotent container that runs on every startup and treats "already done"
as success is what actually made this deployment portable across
machines, not any amount of documentation explaining the manual step
better.

Pin everything, including a "latest"-tagged image. Every `:latest` image
in this stack — `signoz/signoz`, `signoz/signoz-otel-collector`,
`curlimages/curl` — resolved to a real, dated version tag at pull time;
those resolved tags and digests are what's actually pinned in the compose
file, not the floating `latest` tag itself.

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

Don't treat a working setup on one machine as proof it'll work on another.
The standalone container ran without issue for a long stretch of testing
before a second machine turned up both the registration lockout and the
ClickHouse startup race — neither of which had anything to do with the
config being wrong, and everything to do with timing and state that only
differ once you leave the machine something was first verified on.

Don't skip verifying against the platform's actual storage or source when
a claim matters. The registration lockout, the ClickHouse startup race,
and the missing exemplar columns were all found by checking real logs, a
real schema, or the vendor's actual source — never by reading what a
feature page or a first successful run seemed to imply. That gap between
"worked once" and "works reliably" is exactly where the real learning
happened across this integration, and it's the same discipline this
project's other documents (particularly
[otel-collector-processors.md](otel-collector-processors.md)) have applied
throughout.
