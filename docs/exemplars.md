# Exemplars: from a metric spike to the exact trace that caused it

## What an exemplar is

A metric is an aggregate. A histogram bucket that says "42 requests took between
8ms and 16ms" tells you nothing about which 42 requests those were. An exemplar
fixes that: it's a single concrete sample attached to the aggregate, one specific
data point with its own timestamp, value, and, most importantly, a trace ID, kept
alongside the bucket it landed in as a thread you can actually pull on.

This isn't something specific to our stack. It's a standard part of the Prometheus
exposition format since 2.26, and part of the OTel metrics data model too. Grafana,
Prometheus, and OTel all treat "a trace ID attached to a metric point" as a
first-class idea.

## How they get emitted

For a metric point to carry an exemplar, whatever code is recording that metric
needs to know the current trace context at that exact moment, which means metrics
and tracing have to be linked inside the process. That happens two different ways
in this app's pipeline, giving us two independent sources of exemplars.

The first is the app's own metrics. When Micrometer records into
`http_server_requests_*` for a request, that request is already being traced,
thanks to Micrometer Tracing and the OTel bridge (see
[telemetry-flow.md](telemetry-flow.md)). Micrometer can see the active span and
attaches its trace ID to the recorded value automatically — no extra code needed on
our end.

The second is Tempo's derived metrics. Tempo's metrics-generator (see
[tempo-metrics-generator.md](tempo-metrics-generator.md)) builds
`traces_spanmetrics_*` histograms directly from the spans it receives, and it
already knows each span's trace ID as it increments a bucket, so it attaches that
too. This is exactly what `send_exemplars: true` in
[`tempo/tempo.yaml`](../tempo/tempo.yaml) turns on.

Neither source attaches an exemplar to every single value it records — that would
mean storing one per raw event, which defeats the whole point of aggregating in the
first place. In practice they keep a small sample, roughly the most recent point per
bucket per scrape or write interval, which is why a bucket with hundreds of requests
behind it still only shows you one exemplar, not hundreds.

There's one more piece required no matter which source you're looking at:
Prometheus only stores exemplars at all if it's started with
`--enable-feature=exemplar-storage`. Without that flag they're silently dropped the
moment they arrive. That's already set in
[docker-compose.yml](../docker-compose.yml).

## What they're for

A dashboard can tell you when latency spiked. It usually can't tell you "show me
one request from that spike" — for that you'd normally have to go dig through logs
or traces separately, often with no reliable key to tie them together. An exemplar
is that key, already attached: click the point on the graph and you land directly
in the one real trace behind it.

It's the mirror image of the `tracesToMetrics` correlation already set up on the
Grafana Tempo datasource, which goes trace → "show me the aggregate trend for this
operation." Exemplars close the loop the other way: metric → one concrete trace.

## Where to find them here, and which metrics

They only show up on histogram bucket metrics, the ones with a `_bucket` suffix,
never on plain counters. We checked this ourselves: `traces_spanmetrics_calls_total`,
a counter, has none. `traces_spanmetrics_latency_bucket`, a histogram, does.

| Metric | Source | Exemplar label |
|---|---|---|
| `http_server_requests_milliseconds_bucket` | the app itself, via Micrometer | `trace_id` |
| `traces_spanmetrics_latency_bucket` | Tempo's metrics-generator, from spans | `traceID` |

Both are live right now in this stack, confirmed repeatedly across sessions with a
handful to a few dozen exemplar-bearing series each, scaling with how much traffic
has run recently. You can query them directly through Prometheus's own API:

```bash
curl -G http://localhost:9090/api/v1/query_exemplars \
  --data-urlencode 'query=http_server_requests_milliseconds_bucket' \
  --data-urlencode "start=$(($(date +%s)-3600))" \
  --data-urlencode "end=$(date +%s)"
```

## Using them in Grafana

They won't show up in the Metrics Drilldown grid view, the sparkline-card layout —
that view just doesn't render exemplars at all. You need a proper time series panel
instead.

Go to Explore, pick the Prometheus datasource, and query a bucket histogram,
something like `http_server_requests_milliseconds_bucket` or
`rate(traces_spanmetrics_latency_bucket[5m])`. Make sure the Exemplars toggle is on
in the query row (it's on by default here, since the datasource supports it), and
use the Time series visualization.

Exemplars show up as small diamond markers sitting right on the graph line. Hover
over one and you'll see its trace ID, value, and timestamp. Click it and Grafana
jumps straight into Tempo, showing you that exact trace. That works because the
Prometheus datasource has `exemplarTraceIdDestinations` pointing at the Tempo
datasource (see
[`grafana/provisioning/datasources/datasources.yaml`](../grafana/provisioning/datasources/datasources.yaml)).
The same thing works from a dashboard panel too, not just Explore, as long as that
panel's query has exemplars turned on.

## How a developer should actually use this

This is the workflow exemplars exist for. You're looking at a latency or
error-rate dashboard, something spikes, and instead of guessing what happened, you
click the exemplar nearest the spike and land in one real trace from that exact
moment: full span tree, downstream calls, exactly where the time went. We happen to
have two independent metric families here, so you get two entry points into the
same trace — either from the app's own first-hand latency metric, or from Tempo's
span-derived one. Use whichever one the dashboard you're already looking at happens
to be built on.
