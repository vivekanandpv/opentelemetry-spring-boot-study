# Trace sampling: head-based and tail-based, both implemented and measured

Every trace this app produces has been exported in full up to this point —
the `probabilistic_sampler` processor in
[otel-collector-config.yaml](../otel-collector-config.yaml) has sat at 100%
since it was added, deliberately as a no-op just to show where the sampling
dial lives. This is the point where that dial actually gets turned. Both
head-based and tail-based sampling are implemented in the config now, one
active and one commented out, and this document covers what each one is,
why you'd reach for one over the other, and — because a sampling policy
that quietly doesn't do what you think it does is worse than no sampling at
all — exact, verified numbers showing it actually working, cross-checked in
both Grafana and SigNoz.

## The two strategies, and why sampling exists at all

Capturing every trace at real traffic volume gets expensive to store and
slow to query, and most of what you'd capture looks the same: a fast,
successful request that tells you nothing you didn't already know from the
ten thousand identical ones before it. Sampling is how you keep the signal
— the errors, the slow outliers, a representative slice of the rest —
without paying to store and query everything.

**Head-based sampling** decides at the very start of a trace, before a
single span exists to look at. It's almost always a coin flip: keep this
trace X% of the time, decided once at the root and carried through to every
downstream span via trace context. `probabilistic_sampler` is this
strategy. It's cheap — no buffering, no memory overhead, no waiting — but
it's blind. Since the decision happens before anything about the request is
known, an error and a boring success get sampled at exactly the same rate.

**Tail-based sampling** waits until a trace is actually finished — every
span from every service has arrived — before deciding, which means it can
apply rules that actually look at what happened: always keep errors, always
keep anything slow, keep a representative slice of everything else.
`tail_sampling` is this strategy. The cost is what you'd expect from
waiting: the collector has to buffer every span belonging to a trace in
memory until it's complete or a timeout passes, which means more memory and
more latency before export, and — in a deployment with more than one
collector — it requires every span of a given trace to land on the same
collector instance so it can actually see the whole thing. That last part
isn't a concern here, since this project runs exactly one collector.

## What's active right now

`tail_sampling` is live in the traces pipeline; `probabilistic_sampler` is
commented out directly above it in the config, ready to swap back in. The
policy set:

- **Keep every error.** Any trace whose root span carries an HTTP status of
  400, 404, 409, or 500 is kept in full, matched via a `string_attribute`
  policy against the `status` attribute Micrometer's WebMvc observation
  sets on the root span.
- **Keep every slow request.** Any trace where a span runs longer than
  50ms is kept, via a `latency` policy. Generous for what this app usually
  does — most responses finish in single-digit milliseconds — but low
  enough to catch real contention if it shows up.
- **Sample the rest at 20%.** Everything that's neither an error nor slow
  gets a `probabilistic` policy at 20%, the same kind of flat percentage
  `probabilistic_sampler` would apply to everything if it were active
  instead.

These three combine as OR, not AND — a trace is kept if *any* policy votes
to keep it, confirmed against the processor's own documentation rather than
assumed. `decision_wait` is set to 10 seconds (buffer each trace's spans
for up to 10s before deciding) and `num_traces: 50000` sizes the in-memory
buffer comfortably above anything this app's load tests actually produce.

### Why the commented-out version is two lines and this one isn't

Looking at the two blocks side by side, the size difference is real but
it's worth being precise about where it actually comes from, because only
part of it is inherent to tail-based sampling as a category.

A small piece of it genuinely is structural. Head-based sampling has
nothing to configure beyond a percentage — it doesn't look at spans,
doesn't wait for anything, doesn't buffer anything, because the whole
decision happens the instant a trace starts. Tail-based sampling, by
definition, has to hold spans in memory until a trace is finished or a
timeout passes, so it needs a handful of lines head-based sampling simply
has no equivalent for: `decision_wait` to say how long to wait,
`num_traces` to say how much memory to set aside for traces still being
decided, `expected_new_traces_per_sec` as a sizing hint. That's maybe three
or four lines of overhead that come with the territory of "wait and see"
instead of "decide immediately."

The rest of the size — and it's most of it — comes from a choice, not a
requirement: this config gives `tail_sampling` three separate policies,
and each one is its own block with its own fields, because each policy
type needs different information to do its job (`string_attribute` needs a
key and a list of values to match, `latency` needs a threshold, `probabilistic`
needs a percentage). Nothing about tail-based sampling forces three
policies. A `tail_sampling` block with a single `probabilistic` policy at
20% would be barely longer than the commented-out version — maybe eight
lines instead of two, all structural overhead, no extra decision logic.

The reason it isn't written that way is that a single-policy version would
be pointless: it would buffer every trace in memory just to make the exact
same blind coin-flip decision `probabilistic_sampler` already makes for
free, paying tail-based sampling's real cost for none of its benefit. The
entire reason to reach for tail-based sampling in the first place is to
make decisions that actually depend on how a trace turned out, and
expressing "what counts as worth keeping" takes roughly a policy block per
rule. So the length here isn't "tail-based sampling is inherently bulky" —
it's "three rules take three blocks." The size is proportional to how much
was actually said, not to which strategy is being used.

### To compare the two strategies directly

Comment out the `tail_sampling` block, uncomment `probabilistic_sampler`,
swap the name in the `traces` pipeline's processor list, and
`docker compose restart otel-collector`. With `probabilistic_sampler` at
20%, you'll see roughly a fifth of *everything* — errors included — rather
than every error and a fifth of the rest. That's the whole difference
between the two strategies made visible: head-based can't tell an error
from a success, so it treats them the same.

## Verified: what actually happened under real traffic

A clean 20-VU, 60-second load test was run against the live pipeline, and
the true request counts (read from `http_server_requests_milliseconds_count`,
Micrometer's own metric — generated independently of trace sampling, so
it's unaffected by anything the collector drops) were compared directly
against what actually landed in Tempo and SigNoz afterward:

| Status | True requests | Kept (Tempo) | Kept (SigNoz) | Retention |
|---|---|---|---|---|
| 400 | 222 | 222 | 222 | **100%** |
| 404 | 574 | 574 | 574 | **100%** |
| 409 | 91 | 91 | 91 | **100%** |
| 201 (create) | 686 | 140 | 140 | 20.4% |
| 204 (delete) | 231 | 39 | 39 | 16.9% |
| 200 (list/get) | 2681 | 1594 | 1594 | 59.5% |

Two things worth calling out, both real and both explainable:

**Every single error was kept, exactly.** Not approximately — 222 of 222,
574 of 574, 91 of 91. And Tempo and SigNoz report identical numbers for
every row, which makes sense once you remember they're both just reading
whatever the collector decided to forward; the sampling decision happens
once, upstream of both.

**200-status traces were kept at 59.5%, nearly three times the 20%
baseline the probabilistic policy alone would produce.** This isn't a
bug — it's the latency policy doing exactly what it's supposed to. The
`200` status covers both the `list` endpoint (returns every customer row in
the database) and single-record `get` calls, and list is measurably
slower under load. A meaningful share of list requests cross the 50ms
threshold and get kept by the latency policy on top of whatever the
probabilistic policy would have kept anyway, while `create` (201) and
`delete` (204) — both fast, single-row operations — landed at 20.4% and
16.9%, right where the 20% baseline says they should.

### A real lesson from measuring this: give it time before you check

The first attempt at this measurement queried Tempo and SigNoz right after
the load test finished and found error retention sitting at roughly 83%,
not 100% — which looked like the policy wasn't working. It wasn't wrong,
it was early. `tail_sampling` buffers a trace for up to `decision_wait`
(10s) before deciding, and then that decision still has to clear the
`batch` processor and the network hop to two backends before it's
queryable. Widening the query window to comfortably exceed
`decision_wait` plus export latency was what took the number from 83% to
exactly 100%. If you're verifying this yourself and the numbers look off
right after a test, that's very likely why — wait another 15-20 seconds
past when you'd naively expect the data to have landed, and check again
before concluding anything's actually broken.

## How to test this yourself

1. Confirm the app and the whole stack are running
   (`docker compose ps`), then run the load test:
   `docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e VUS=20 -e DURATION=60s grafana/k6 run - < load-testing/customer-load-test.js`.
2. Get the true per-status counts from Prometheus directly, so you have
   ground truth independent of sampling: query
   `http_server_requests_milliseconds_count{job="opentelemetry-spring-boot-study"}`
   before and after the run and take the difference per `status` label —
   this metric is generated by the app itself, not derived from traces, so
   it's unaffected by whatever the collector decides to drop.
3. Wait at least 20-25 seconds past the end of the test — see the timing
   note above.
4. **In Grafana**: open Tempo's search (Explore → Tempo datasource) and
   query with TraceQL, e.g. `{span.status="404"}`, `{span.status="200"}`.
   Compare the result count against the true count from step 2 for that
   same status.
5. **In SigNoz**: the equivalent check without a UI is a direct ClickHouse
   query, since traces ultimately land in `signoz_traces.signoz_index_v3`:
   `SELECT attributes_string['status'] AS status, count() FROM signoz_traces.signoz_index_v3 WHERE resource_string_service$$name = 'opentelemetry-spring-boot-study' AND parent_span_id = '' GROUP BY status` —
   or use the Traces explorer in the UI itself and filter by
   `status = 404` (or whichever code you're checking) the same way.
6. What to expect: 400/404/409 counts should match the true count from
   step 2 exactly (or very close — a handful of stragglers can still be
   mid-flight). 200/201/204 counts should sit well below their true
   counts, in the neighborhood of the configured 20% baseline, with 200
   likely running higher than the other two for exactly the reason
   explained above.

## Best practices

Verify a sampling policy against real numbers, not just "traces are
showing up in the UI." A collector can be running, exporting, and visibly
producing *some* traces in Grafana or SigNoz while still silently failing
to keep every error — the only way to know the policy is doing what it
says is comparing kept counts against a true count from a source sampling
can't touch, the way this document does with Micrometer's own metrics.

Give tail-based decisions time to actually land before you judge them.
`decision_wait` plus batching plus network hops to two backends adds up to
real seconds, not milliseconds — query too early and a working policy
looks broken.

Keep the "keep everything interesting" policies (errors, latency) generous
enough to genuinely catch what you care about, and let the probabilistic
policy carry the cost-control weight for the boring majority. Here, 50ms
is generous for an app that normally responds in single-digit
milliseconds specifically so it doesn't accidentally start acting like a
second probabilistic filter on ordinary traffic.

## Antipatterns

Don't assume "some traces are showing up" means sampling is configured
correctly. It's entirely possible to have a policy that's technically
valid YAML, technically running, and technically keeping *a* subset of
traces that isn't the subset you actually intended — the 83%-versus-100%
measurement earlier in this document is exactly that trap, caught only by
comparing against ground truth rather than trusting the first check.

Don't reach for tail-based sampling by default. If you don't need
outcome-aware decisions — if a flat percentage genuinely is fine for your
use case — head-based sampling gets you most of the cost control for a
fraction of the memory and latency cost. Tail-based earns its complexity
specifically when losing an error trace to a blind coin flip is a real
problem, not as a default upgrade.

Don't set `decision_wait` shorter than your traces actually take to
complete, and don't forget that "complete" includes network jitter under
real load, not just the happy-path duration you tested with locally. A
`decision_wait` that's too tight silently produces the same kind of
partial, confusing retention numbers this document had to work through to
get a clean measurement.
