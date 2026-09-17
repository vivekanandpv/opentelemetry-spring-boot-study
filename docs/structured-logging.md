# Structured logging

Two related but separate things changed here. The app's console output is now
machine-parseable JSON, using Spring Boot's native structured logging, and the
app's own code now emits meaningful log events with structured fields, using
SLF4J's fluent key-value API, instead of plain interpolated strings.

## Console: native structured (ECS) logging

Spring Boot 4.1 ships built-in structured console and file logging out of the box,
in ECS (Elastic Common Schema), GELF, or Logstash format, and you only need one
property to turn it on. No custom JSON encoder library required:

```yaml
logging:
  structured:
    format:
      console: ecs
```

Here's a gotcha we ran into. That property is normally applied automatically by
Boot's own default logging setup, but this project has a custom
[`logback-spring.xml`](../src/main/resources/logback-spring.xml) (needed for the
OTel Logback appender — see [telemetry-flow.md](telemetry-flow.md)). Supplying your
own config file opts you out of that automatic switch. Boot's stock
`console-appender.xml` include just hardcodes a plain text pattern no matter what
the property says. The fix is to wire `StructuredLogEncoder` in explicitly, reading
the desired format from the same property so it's still configurable from
`application.yaml`:

```xml
<springProperty name="consoleLogStructuredFormat" source="logging.structured.format.console"/>
<appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="org.springframework.boot.logging.logback.StructuredLogEncoder">
        <format>${consoleLogStructuredFormat}</format>
    </encoder>
</appender>
```

Here's what a real console line looks like once that's wired up:

```json
{"@timestamp":"2026-09-17T15:11:02.221913Z","log":{"level":"INFO","logger":"...CustomerServiceImpl"},
 "service":{"name":"opentelemetry-spring-boot-study"},"message":"Customer created",
 "traceId":"32764b60c112dd5452fb5da105aff6df","spanId":"669a61de00d6c08a","customerId":2069,"ecs":{"version":"8.11"}}
```

Trace correlation turned out to be automatic, and we didn't have to write any code
for it. `traceId` and `spanId` show up because Micrometer Tracing's OTel bridge
populates MDC with the active span's IDs whenever tracing is on, and Spring Boot's
ECS formatter includes that MDC map as JSON members. We confirmed this straight
from test output: the exact same log statement, run once inside
`CustomerApiIntegrationTest` (a real Spring context with tracing on) and once
inside `CustomerServiceImplTest` (a plain Mockito unit test with no tracing
infrastructure at all), carries `traceId`/`spanId` only in the former. Exactly what
you'd expect, and nothing to configure either way.

## Code: structured log statements

We added these at the points that are actual business events — customer created,
updated, and deleted, in
[`CustomerServiceImpl`](../src/main/java/dev/vivekanand/opentelemetryspringbootstudy/customer/service/CustomerServiceImpl.java)
— using SLF4J 2.x's fluent API with `addKeyValue` instead of string interpolation:

```java
log.atInfo()
    .addKeyValue("customerId", response.id())
    .log("Customer created");
```

Notice it logs the id, not the email. That's deliberate — better to avoid PII in
logs at INFO level when an opaque identifier already tells you what you need. It's
also deliberately not duplicated in the controller layer: one log line per business
event, at the layer that actually knows whether it happened, not one at every layer
a request happens to pass through.

`GlobalExceptionHandler` logs each handled business exception at DEBUG. A 404 or
409 is a normal, expected outcome from the caller's point of view, not something
worth surfacing as a warning about the service's own health. We also added a
catch-all `@ExceptionHandler(Exception.class)` that didn't exist before —
previously, anything unmapped would fall through to Spring's default error handling
with no log entry of ours pointing at it. That catch-all logs at ERROR with the
real exception attached as the cause, full stack trace and all, but returns a
generic message to the client, since the exception's own message might contain
internal detail — a raw SQL error, a class name — that shouldn't leave the service
boundary.

## Getting these fields all the way to Loki, not just the console

Here's a second gotcha, also one we only caught by checking. The `addKeyValue`
fields, like `customerId`, showed up correctly in the console JSON right away, but
were missing from that same log record once it reached Loki through the OTel
Logback appender. That appender has its own separate capture flags, and they're all
off by default:

```xml
<appender name="OpenTelemetry" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
    <captureKeyValuePairAttributes>true</captureKeyValuePairAttributes>
</appender>
```

We confirmed the fix with a live query after turning it on — `customerId` is now a
real, independently filterable field in Loki, right alongside the trace and span
correlation:

```
{service_name="opentelemetry-spring-boot-study"} | customerId != ""
```

## Viewing it

For the local console, pipe through `jq` for something readable:
`./mvnw spring-boot:run | jq .` — or just drop the
`logging.structured.format.console` property for a while if you want the old
human-readable pattern back.

In Grafana Explore, filter Loki by `service_name`, then narrow further with
`| customerId="<id>"` to follow everything that happened to one customer, or
`| trace_id="<id>"` to see the full structured log context for one request. See
[exemplars.md](exemplars.md) if you want to jump there starting from a trace or a
metric instead.
