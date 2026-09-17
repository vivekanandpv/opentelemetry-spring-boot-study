# Observability libraries — what we use and why

This project exports traces, metrics, and logs via OTLP to an OTel Collector, and
it does that with exactly one starter:
`org.springframework.boot:spring-boot-starter-opentelemetry`, plus the Logback
bridge for logs. Everything below is here to explain what the other commonly-seen
libraries actually do, and why they're not also in this project — mixing them in is
exactly what causes the confusion, and the duplicate or conflicting telemetry, that
this note exists to head off.

## The two "OpenTelemetry Spring Boot starters" — do not combine

These share a very similar name, but they're built by different teams, for
different instrumentation models entirely:

| | `org.springframework.boot:spring-boot-starter-opentelemetry` | `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter` |
|---|---|---|
| Maintainer | Spring team, part of Spring Boot core since 4.0 | The OpenTelemetry community, via the `opentelemetry-java-instrumentation` project |
| Instrumentation API | Micrometer's `Observation`/`Tracer`, bridged to real OTel spans | The OTel API/SDK directly, via `opentelemetry-sdk-extension-autoconfigure` |
| Config surface | `management.opentelemetry.*`, `management.otlp.*` | `otel.*` properties, or `OTEL_*` env vars |
| Plugs into Spring's built-in MVC/JDBC instrumentation | Yes, reuses `WebMvcObservationAutoConfiguration` and friends | No, brings its own instrumentation modules |
| This project | Used | Not used |

The reason not to run both: each one bootstraps its own `OpenTelemetry` SDK
instance and tries to wire up its own tracer and exporter providers. Add both and
you end up with two independent pipelines competing for the same job — duplicate
spans, doubled export traffic, or bean-resolution conflicts, and none of it is
obvious just from the symptoms.

## Metrics export: push (OTLP) vs. pull (Prometheus)

| | `micrometer-registry-otlp` | `micrometer-registry-prometheus` |
|---|---|---|
| Model | Push: the app POSTs metric batches to an OTLP receiver on a timer, every 60 seconds by default | Pull: the app exposes `/actuator/prometheus`, and something scrapes it periodically |
| Needs Actuator? | No | Yes, to expose the scrape endpoint |
| Fits "everything flows through the collector"? | Yes | Only if the collector itself scrapes Prometheus, which is a different architecture |

We went with OTLP push because the whole point here is a single ingestion point —
the collector — receiving all three signals the same way.
`spring-boot-starter-opentelemetry` already pulls `micrometer-registry-otlp` in
transitively, so there's no need to declare it separately.
`micrometer-registry-prometheus` isn't used at all; you'd only add it if some
backend specifically needed to scrape Prometheus-format metrics directly off the
app.

## `micrometer-tracing-bridge-otel` — don't declare it yourself

This is the glue that turns Micrometer's `Tracer` calls into real OTel spans. It's
already a transitive dependency of `spring-boot-starter-opentelemetry`, and its
version is managed by Spring's BOM to match the rest of the OTel SDK.

If you declare it explicitly with your own version — like the `1.6.5` in the
original snippet — you risk exactly the kind of version-skew bug we ran into with
the OTel Logback appender in this project: a `NoClassDefFoundError` from an
incompatible OTel API version sitting alongside the one the SDK expects. Better to
just let the starter manage it.

## `spring-boot-starter-actuator` — orthogonal, not a telemetry library

Actuator is the management and HTTP endpoint layer: `/actuator/health`,
`/actuator/loggers`, `/actuator/prometheus` if that registry happens to be present,
and so on. On its own it doesn't export telemetry anywhere.

It's not required for OTLP export — `spring-boot-starter-opentelemetry` exports on
its own background schedule, completely independent of Actuator. It's still worth
adding if you want ops endpoints, health and readiness probes for Kubernetes, or
`/actuator/loggers` for changing log levels at runtime. It can sit alongside our
stack without any conflict; it's just answering a different question.

## Summary: what's actually in `pom.xml`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-opentelemetry</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-logback-appender-1.0</artifactId>
    <version>2.30.0-alpha</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api-incubator</artifactId>
    <version>1.62.0-alpha</version>
</dependency>
```

Everything else from the original list — `spring-boot-starter-actuator`,
`micrometer-registry-prometheus`, `micrometer-registry-otlp`, the
`io.opentelemetry.instrumentation` flavor of `opentelemetry-spring-boot-starter`,
and an explicitly-versioned `micrometer-tracing-bridge-otel` — is either redundant,
since it's already pulled in transitively, or actively conflicting, since it means
a second OTel SDK bootstrap path, given this project's "capture via Micrometer,
export via OTLP to the collector" architecture.
