# Tempo's metrics-generator: how traces turn into metrics

This is the mechanism behind the `Tempo → Prometheus` arrow in
[telemetry-flow.md](telemetry-flow.md), and it's easy to miss because nothing in the
app or the collector configures it. It all happens inside Tempo itself, working off
data Tempo already has for its own storage.

## The problem it solves

Two features on Grafana's Tempo datasource need data that doesn't exist anywhere
yet.

Trace → metrics (the `tracesToMetrics` setting) lets you click a span and ask "what
did the request rate or latency for this service look like around this time?" That
needs an actual metrics backend with per-service RED data, and Tempo itself only
stores raw traces, not aggregated time series.

The service graph (`serviceMap`) is a node-and-edge diagram of which services call
which, with request rate, error rate, and duration on each edge. Nothing emits that
directly either — it has to be inferred by looking at span parent/child
relationships across a lot of traces.

Rather than asking the app to emit a second set of RED metrics alongside its regular
ones, Tempo derives both of these straight from the spans it already receives, in
real time, before they're even written to trace storage. That component is called
the metrics-generator.

## What it produces

It's configured in [`tempo/tempo.yaml`](../tempo/tempo.yaml) via
`overrides.defaults.metrics_generator.processors: [service-graphs, span-metrics]`
(along with `generate_native_histograms: both`, so both classic
`histogram_quantile()` queries and native-histogram queries work against the same
data). There are two independent processors here, each producing its own family of
metrics.

**`span-metrics`** builds one set of RED metrics per `(service, span name, status)`
combination, generated straight from every span's start/end time and status code:
- `traces_spanmetrics_calls_total` is the request count, the "R" in RED.
- `traces_spanmetrics_latency_bucket` / `_sum` / `_count` form a latency histogram,
  the "D" in RED. Error rate comes out of filtering `calls_total` by status.
- `traces_spanmetrics_size_total` tracks total span payload size.

**`service-graphs`** works differently: it matches client spans to the server spans
they called, using span parent/child linkage, and produces metrics per `(client,
server)` edge:
- `traces_service_graph_request_total`
- `traces_service_graph_request_client_seconds_*` and
  `traces_service_graph_request_server_seconds_*`

## Where the metrics actually go

The metrics-generator doesn't expose anything to scrape. It pushes what it
generates straight to Prometheus via remote-write, the same way everything else in
this stack does:

```yaml
metrics_generator:
  storage:
    remote_write:
      - url: http://prometheus:9090/api/v1/write
        send_exemplars: true
```

`send_exemplars: true` is what attaches the originating trace ID to each generated
data point as a Prometheus exemplar. That's the piece that lets you click a spike on
a `traces_spanmetrics_calls_total` graph in Grafana and land straight on the trace
that caused it — the reverse direction of `tracesToMetrics`.

## How this differs from the app's own metrics

This app already exports its own HTTP metrics directly: `http_server_requests_*`,
pushed via OTLP straight from Micrometer (see [telemetry-flow.md](telemetry-flow.md)).
So for this one service, Prometheus actually ends up holding two
independently-computed views of the same requests.

| | `http_server_requests_*` | `traces_spanmetrics_*` |
|---|---|---|
| Computed by | the app itself, in-process | Tempo, from spans it received |
| Subject to trace sampling? | No — metrics aren't sampled | Yes — undercounts if `management.tracing.sampling.probability` < 1.0 |
| Exists for services with no direct Micrometer/OTLP metrics export | No | Yes — this is the real value: any traced service gets RED metrics for free |

We run trace sampling at `1.0` (see
[application.yaml](../src/main/resources/application.yaml)), so in this project the
two views should agree. The real payoff of `span-metrics` shows up elsewhere: for
services that aren't instrumented for metrics at all, or third-party and legacy
services you can only get spans from, they still get RED dashboards and
service-graph visibility, derived purely from tracing data.

## Verified working

I queried this directly against Prometheus after sending a few requests through the
app:

```
sum(rate(traces_spanmetrics_calls_total{service="opentelemetry-spring-boot-study"}[5m]))
→ 0.0142  (non-zero, real data)
```

That returned real, non-zero data. Both metric families showed up in Prometheus —
`traces_spanmetrics_*` and `traces_service_graph_*` — confirming the generator is
actually live, and that the `tracesToMetrics`/`serviceMap` correlation in the
Grafana Tempo datasource has real data behind it, not just config that looks right
on paper.
