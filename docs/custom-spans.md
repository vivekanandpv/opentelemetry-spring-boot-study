# Custom spans: seeing inside the automatic ones

Right now the only spans this app produces on its own come from Spring's built-in
instrumentation: one per incoming HTTP request, named something like `http post
/api/v1/customers`. That's the entire trace. If a request is slow, that single span
is all you get, with no breakdown of where the time actually went. And anything
that isn't triggered by an HTTP request, like the customer seeder running at
startup, doesn't get a span at all, since there's no request for one to attach to.

Custom spans fix both problems. They let you mark out a meaningful chunk of work in
your own code, give it a name, tag it with whatever context matters, and have it
show up in the trace, either as a child of whatever span was already running, or as
its own root trace if nothing was running yet.

## What problem this actually solves

Two things.

First, visibility inside a request. Before this change, a trace for `POST
/api/v1/customers` was one flat span with a duration and nothing else. Now it has a
child span, `customer.create`, covering the actual business logic: the
duplicate-email check, the save, the response mapping. If that request is ever
slow, you can tell right away whether the time went there or somewhere else in the
request pipeline, instead of guessing.

Second, coverage for work that never touches HTTP. The seeder runs once at startup,
outside any request, and inserts a hundred rows. Before this, that whole operation
was invisible to tracing — the only signal you had was a log line at the end. Now
it gets its own root span, `customer.seed`, so you can see it as a real trace with
a real duration, the same way you'd see any HTTP-triggered request.

## How it's implemented here

We used Micrometer's `Observation` API rather than the raw OpenTelemetry API
directly. That's a deliberate choice, and it matches how the rest of this project
is wired: everything already flows through Micrometer as the instrumentation
facade, bridged to OTel underneath (see
[observability-libraries.md](observability-libraries.md) for why). Reaching for
`io.opentelemetry.api.trace.Tracer` directly here would mean going around that
bridge, which is exactly the kind of inconsistency that caused real problems
earlier in this project.

The pattern is the same everywhere it's used, in
[`CustomerServiceImpl`](../src/main/java/dev/vivekanand/opentelemetryspringbootstudy/customer/service/CustomerServiceImpl.java)
and in
[`CustomerSeeder`](../src/main/java/dev/vivekanand/opentelemetryspringbootstudy/config/CustomerSeeder.java):

```java
Observation observation = Observation.createNotStarted("customer.create", observationRegistry);
return observation.observe(() -> {
    // ... the actual work ...
});
```

`createNotStarted` builds the observation without starting it yet, which gives you
a chance to attach tags before or during the run. `observe()` then starts it, runs
the block, and stops it when the block finishes, whether it returns normally or
throws. If it throws, the exception is recorded on the observation and rethrown
unchanged, so callers never see any difference in behavior — only the trace gains
an error marker.

One thing worth calling out: `Observation` doesn't just produce a span. It produces
a span *and* a timer metric, from the same one call. That's the whole point of the
abstraction — one API, both signals, automatically correlated. You can see this
directly in Prometheus after running the app: alongside the five service-layer
spans there are five new metric families, `customer_create_milliseconds_bucket`
and so on, and they carry exemplars pointing straight back at the spans that
produced them. It's a third, independent source of exemplars in this stack, on top
of the two already documented in [exemplars.md](exemplars.md).

## Best practices we followed

**Name spans after the operation, not the method.** `customer.create` reads
clearly in a trace view; `CustomerServiceImpl.create` doesn't tell you anything
about what actually happened, only where the code lives.

**Don't span everything.** Every method call wrapped in a span adds overhead and
noise, and a trace with fifty tiny spans in it is harder to read than one with five
meaningful ones. We instrumented the five public service methods and the one
startup batch job, and stopped there. There was no reason to wrap the mapper or the
repository calls individually — those are already implicit inside their parent
span's duration, and nobody's going to ask "how long did the mapping step take"
often enough to justify the extra span.

**Split cardinality correctly.** Micrometer's `Observation` API gives you two ways
to tag something: `lowCardinalityKeyValue`, which becomes a label on the metric as
well as an attribute on the span, and `highCardinalityKeyValue`, which only ever
becomes a span attribute. A customer id is a textbook case for the high-cardinality
path. There could be thousands of them, and turning each one into a Prometheus
label would blow up the metric's cardinality and could genuinely make Prometheus
fall over. As a span attribute, though, there's no such limit, since Tempo doesn't
aggregate spans into time series the way Prometheus does with metrics.

**Let errors show up as errors.** We didn't add any special-case handling to catch
`CustomerNotFoundException` or `DuplicateEmailException` before they reach
`Observation`. They propagate normally, get recorded as span errors, and still get
rethrown to the exception handler exactly as before. That does mean a routine 404
shows up with a red status in Tempo, same as a genuine failure would. That's a fair
trade here — the trace is telling the truth about what happened, and separating
"this failed" from "this failure should page someone" is what alerting rules are
for, not something the tracing layer should quietly decide on your behalf.

**Use a real no-op registry in tests, not a mock.** `ObservationRegistry.NOOP` is a
genuine implementation that runs the wrapped code without recording anything, and
it's what the unit tests for `CustomerServiceImpl` and `CustomerSeeder` use now.
Mocking `Observation`'s fluent chaining with Mockito would work, technically, but
it's fragile, and `NOOP` already does precisely what the tests need.

## Verified working

After starting the stack and sending some real requests, checking directly against
Tempo confirmed the whole chain.

The seeder's span landed as its own root trace, named `customer.seed`, with no
parent — exactly as expected for something running at startup.

A `POST` request produced a trace with two spans: `http post /api/v1/customers` as
the parent, and `customer.create` nested underneath it, carrying `customer.id` as
an attribute.

Searching Tempo directly for that attribute, `{span.customer.id="101"}`, returned
exactly the three traces that touched that customer: the create, the get, and the
update. That's the span attribute doing real, useful work, not just sitting there
for decoration.

Grafana's own datasource proxy to Tempo was able to fetch that trace and return
both spans, confirming this isn't just visible to Tempo's raw API — it's what
Grafana itself would render.

And in Prometheus, the new `customer_create_milliseconds_bucket` metric (and the
four siblings for the other operations) showed up with a working exemplar, linking
straight back to the exact span that produced it.
