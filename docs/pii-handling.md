# PII in telemetry: three processors, one real leak, and where it was actually hiding

Every document in this project so far has treated telemetry as something to
generate and observe. This one is about something telemetry does by
accident if you're not paying attention: it can carry personal data out of
your application and into three or four different storage systems, none of
which were designed to be a system of record for anyone's email address.
This document covers what that risk actually looks like in a real,
running app — not a hypothetical — and how three complementary
otel-collector processors close it.

## Why this matters

Telemetry pipelines are an unusually easy place for personal data to leak,
for a boring structural reason: nobody designs a trace attribute or a log
line the way they design a database column. A database schema gets a
review; an exception message gets written once, in the moment, to explain
what went wrong to whoever's debugging it next. Nobody sits down and asks
"should this string end up replicated into Tempo, Loki, and a second
backend, kept for weeks, and queryable by anyone with dashboard access?"
It just happens, because the exception's own message already had the
answer to "which email address" baked into it, and OpenTelemetry's
automatic instrumentation faithfully records exceptions in full because
that's exactly what makes it useful for debugging.

That's the actual tension: the same completeness that makes telemetry
valuable for debugging is what makes it a real vector for personal data
to travel somewhere it was never supposed to go. GDPR and similar
regulations don't carve out an exception for "it was in a stack trace, not
a database column" — personal data is personal data regardless of which
system it's sitting in, and a telemetry backend that nobody thought to
audit is exactly the kind of place a data-protection review finds the
thing nobody meant to keep.

OpenTelemetry's collector is a genuinely good place to deal with this,
for the same reason it's a good place to deal with sampling or framework
log noise: it sits between every service and every backend, so a fix made
once at the collector protects every signal going to every destination,
without touching application code or asking every service team to
remember to scrub their own exception messages. That's not a reason to
skip being careful in the application itself — the right instinct is
still "don't put PII in things you don't have to" — but the collector is
where you catch what got through anyway.

## The real leak this app had

This isn't a constructed example. [otel-collector-processors.md](otel-collector-processors.md)
documented finding it: when a create or update request fails because the
email is already taken, `CustomerServiceImpl` throws
`DuplicateEmailException("Customer already exists with email: " + email)`,
and that message — raw email included — gets recorded automatically by
Micrometer's Observation API the moment the exception crosses the
`customer.create` span. Verifying it properly this time (rather than just
noting it) turned up not one leak but three, each in a different part of
the span's own data:

1. **`exception.message`**, an attribute on the span's `exception` event.
2. **`exception.stacktrace`**, a second attribute on the same event — the
   full stack trace, which naturally repeats the exception's message on
   its first line.
3. **`span.status.message`** — a field on the span's own OTel Status
   object, separate from both the event and any span-level attribute, and
   easy to miss because it isn't listed alongside "attributes" in most
   people's mental model of what a span contains. Micrometer's Observation
   API sets this from the exception's message too, independently of the
   event.

All three carried the exact same raw email, in three structurally
different places, which is exactly why finding all of them mattered —
fixing only the obvious one (`exception.message`) would have left the
same personal data sitting in two other fields nobody thought to check.

## The three processors, and what each one is actually for

[`otel-collector-config.yaml`](../otel-collector-config.yaml) now runs
three processors that don't overlap in what they can reach, which is the
point — each covers ground the other two can't.

**`attributes/hash_customer_id`** pseudonymizes rather than removes. It
runs the `attributes` processor's `hash` action against `customer.id` on
spans and `customerId` on logs, turning the database's raw sequential ID
into a one-way hash before it ever leaves the collector. This is the
right tool when you want to keep a value *useful* — you can still tell
that two spans or two log lines refer to the same customer, and correlate
across a single session — without exposing the actual identifier a
database lookup could resolve back to a real person.

**`redaction`** is the allow-list. `allow_all_keys: false` plus an
explicit `allowed_keys` list means any attribute key that isn't on the
list gets stripped entirely, span-level or resource-level, no exceptions.
This is the "catch what hasn't been invented yet" layer: if someone adds
a new attribute next month that happens to carry a customer's name, this
processor drops it on sight, without anyone having to remember to update
a redaction rule first. `blocked_values` adds a second check on top —
even an attribute that *is* on the allow-list gets its value masked if it
matches an email-shaped pattern, in case something safe-looking ever
carries something it shouldn't.

**`transform`** is the scalpel. Built on OTTL, it's the only one of the
three that can reach into a span *event's* attributes or the span's own
status message — both `redaction` and `attributes` operate on span-level
attributes only, and simply have no path to either of those places. This
is exactly why the other two processors, correctly configured, would
still have left this app's real leak untouched: `exception.message`,
`exception.stacktrace`, and `span.status.message` all live somewhere
neither of them can see. `transform`'s `replace_pattern` function finds
the email-shaped substring inside each field and replaces just that part
with `[REDACTED_EMAIL]`, leaving the rest of the message intact — you
still get "Customer already exists with email: [REDACTED_EMAIL]," which
is exactly as useful for debugging as the original, minus the one thing
that shouldn't have been there. The same processor also carries a
`log.body` rule that does the same thing to log lines, which is currently
dormant (see below) but ready the moment it isn't.

Three different reach, three different techniques — hash what you want
to keep useful, allow-list what you want to guarantee never leaks
unexpectedly, and surgically edit the handful of specific fields where
something's already gotten through and the fix is "keep the message, lose
the email."

## A real mistake made and caught while building this

The first version of the `redaction` allow-list only listed the
span-level attribute keys this app actually uses — `uri`, `method`,
`status`, `customer.id`, and so on. Running it against real traffic broke
something immediately obvious: Tempo started showing
`rootServiceName: <root span not yet received>` on every trace, because
`service.name` had vanished. So had `collector.name`, `deployment.environment`,
and every other resource-level attribute — the redaction processor's
allow-list scope turned out to apply to *resource* attributes too, not
just span attributes the way a first read of its documentation suggested.
Confirmed directly: a trace fetched from Tempo showed exactly one resource
attribute left, `redaction.redacted.count: 6` — the redaction processor's
own diagnostic counter, replacing the six resource attributes it had just
stripped because none of them were on the list.

The fix was adding `service.name` and the other resource-level keys to
`allowed_keys` explicitly. The reason this is worth writing down rather
than quietly fixing and moving on: this is a PII processor that, left
misconfigured, would have broken every downstream feature that depends on
knowing which service a trace belongs to — arguably worse than the leak
it was meant to prevent, and it would have shipped looking like it
worked, since nothing about a missing `service.name` throws an error.
The only way this got caught was checking the actual exported trace
against what was expected, not trusting that "the collector started
without errors" meant the config was right.

## What's dormant, and why it's implemented anyway

`GlobalExceptionHandler` also logs `ex.getMessage()` directly as the log
*body* for both `DuplicateEmailException` and `CustomerNotFoundException`,
at `DEBUG` level. Checked directly: this app's logging level defaults to
`INFO`, so that `DEBUG` line never gets emitted, and the raw email never
actually reaches Loki through this path today — confirmed by triggering a
real duplicate-email conflict and searching Loki for the probe email
immediately after, finding nothing.

The `transform` processor's `log.body` redaction rule is written and wired
in anyway. The reasoning: PII protection that only works as long as
nobody ever changes a logging level is protection that's one config
change away from silently failing. Root cause — not logging raw personal
data at `DEBUG` in the first place — is still the better fix and belongs
in application code, not this document's scope. But the collector-side
rule costs nothing while it's dormant and closes the gap the moment it
isn't.

## How to verify this yourself

1. Trigger the real leak directly: `POST` the same customer twice to
   `/api/v1/customers` so the second one gets a 409, wait roughly 15
   seconds for `tail_sampling`'s decision buffer and export latency to
   clear, then pull the trace. In Grafana, use Tempo's TraceQL search for
   `{span.status="409"}` and open the most recent result. In SigNoz,
   the same trace is reachable through the UI's trace explorer, or
   directly via `SELECT attributes_string['exception.message'],
   status_message FROM signoz_traces.signoz_index_v3 WHERE name =
   'customer.create' ORDER BY timestamp DESC LIMIT 1` against its
   ClickHouse store. What to expect: `exception.message`,
   `exception.stacktrace`, and the span's status message all read
   "...email: [REDACTED_EMAIL]" — never the real address.
2. Confirm pseudonymization: create a customer, note the numeric `id` the
   API returns, then check that same span's `customer.id` attribute (or
   the `customerId` field on its "Customer created" log line in Loki).
   It should be a 64-character hash, never the plain number.
3. Confirm the allow-list is scoped correctly, not just present: check
   that `rootServiceName` still resolves correctly in Tempo (or that
   `service.name` still shows up in a resource attribute dump from either
   backend) rather than assuming a processor that starts without errors
   is configured correctly. This exact check is what caught the mistake
   described above.
4. Run the full load test and sweep for leaks in bulk rather than trusting
   a handful of manual checks: query ClickHouse (or Tempo) across the
   whole run for any span whose message, stacktrace, or status still
   contains an `@`-shaped pattern. Zero is the only acceptable answer.

## Best practices

Match the tool to where the data actually lives, not to what sounds most
thorough. Reaching for `transform`/OTTL everywhere because it's the most
powerful option means writing and maintaining redaction logic by hand for
things `attributes` or `redaction` would handle with a two-line
declarative rule. Reach for the scalpel only where the blunter tools
genuinely can't reach — span events and status fields, in this app's
case.

Verify a redaction or allow-list processor by checking what's *left*, not
just what's gone. The dangerous failure mode isn't "the PII is still
there" — that's obvious and gets caught fast. It's "the processor also
quietly ate something you needed," the way this app's own `service.name`
disappeared. Check the shape of what survives, not only the absence of
what shouldn't.

Prefer pseudonymization over deletion when the value still has legitimate
use. Hashing `customer.id` keeps traces and logs joinable by customer
without exposing the identifier itself — strictly more useful than
deleting the field outright, for the same privacy outcome.

Treat "this data path is currently disabled" as a reason to fix it anyway,
not a reason to skip it. The dormant log-body rule here costs nothing
while `DEBUG` logging stays off and prevents a real, live leak the moment
someone flips that switch for a debugging session and forgets to flip it
back.

## Antipatterns

Don't assume a processor's documented scope matches its actual behavior
without checking against real output. The redaction processor's own
README describes it in terms of "span, log, and metric datapoint
attributes"; it took a live trace losing its `service.name` to learn that
resource attributes are in scope too. Read the docs to know where to
start looking, then verify against what actually comes out the other
side.

Don't rely on a single processor type to cover every PII surface in a
signal. `redaction` and `attributes` both stop at span-level attributes;
neither can see inside a span event or a status message. An app that only
configured those two would still have shipped this exact leak, fully
convinced it was covered, because nothing about either processor's
successful startup would have said otherwise.

Don't treat "the collector redacts it" as license to keep putting PII in
exception messages, log bodies, or anything else in application code.
The collector is the safety net for what gets through despite reasonable
care, not a reason to stop taking that care. This app's own exception
message is still going to keep containing a raw email at the source; the
collector is masking it in flight, not fixing why it's there.
