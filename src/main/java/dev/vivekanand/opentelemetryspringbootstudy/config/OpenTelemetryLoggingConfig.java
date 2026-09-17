package dev.vivekanand.opentelemetryspringbootstudy.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.context.annotation.Configuration;

/**
 * Spring registers its {@link OpenTelemetry} SDK as a plain bean rather than
 * {@code GlobalOpenTelemetry}, so the Logback appender needs to be pointed at it
 * explicitly for log records to be exported.
 */
@Configuration
public class OpenTelemetryLoggingConfig {

    public OpenTelemetryLoggingConfig(OpenTelemetry openTelemetry) {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
