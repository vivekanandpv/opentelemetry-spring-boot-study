package dev.vivekanand.opentelemetryspringbootstudy.customer.metrics;

import dev.vivekanand.opentelemetryspringbootstudy.customer.repository.CustomerRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerMetricsTest {

    @Mock
    private CustomerRepository customerRepository;

    private SimpleMeterRegistry registry;
    private CustomerMetrics customerMetrics;

    @BeforeEach
    void setUp() {
        // a real, lightweight in-memory registry: exercises the actual Counter/Gauge/DistributionSummary
        // registration and math, without needing the full OTLP/Prometheus export pipeline
        registry = new SimpleMeterRegistry();
        customerMetrics = new CustomerMetrics(registry, customerRepository);
    }

    @Test
    void recordDuplicateEmailConflict_shouldIncrementCounter() {
        customerMetrics.recordDuplicateEmailConflict();
        customerMetrics.recordDuplicateEmailConflict();

        assertThat(registry.get("customer.email.duplicate").counter().count()).isEqualTo(2.0);
    }

    @Test
    void recordValidationFailure_shouldIncrementAsyncCounterAndRecordHistogram() {
        customerMetrics.recordValidationFailure(3);
        customerMetrics.recordValidationFailure(1);

        assertThat(registry.get("customer.validation.errors").functionCounter().count()).isEqualTo(2.0);
        assertThat(registry.get("customer.validation.violations").summary().count()).isEqualTo(2);
        assertThat(registry.get("customer.validation.violations").summary().totalAmount()).isEqualTo(4.0);
    }

    @Test
    void recordCustomerCreated_shouldGrowRecentBufferGaugeAndNetCreatedGauge() {
        customerMetrics.recordCustomerCreated(1L);
        customerMetrics.recordCustomerCreated(2L);

        assertThat(registry.get("customer.recent.buffer.size").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("customer.net.created").gauge().value()).isEqualTo(2.0);
    }

    @Test
    void recordCustomerCreated_shouldCapRecentBufferGaugeAtFiftyEntries() {
        for (long id = 1; id <= 60; id++) {
            customerMetrics.recordCustomerCreated(id);
        }

        assertThat(registry.get("customer.recent.buffer.size").gauge().value()).isEqualTo(50.0);
        // the buffer is capped, but the net-created tally is a running count, not tied to buffer capacity
        assertThat(registry.get("customer.net.created").gauge().value()).isEqualTo(60.0);
    }

    @Test
    void recordCustomerDeleted_shouldDecreaseNetCreatedGauge() {
        customerMetrics.recordCustomerCreated(1L);
        customerMetrics.recordCustomerCreated(2L);
        customerMetrics.recordCustomerDeleted();

        assertThat(registry.get("customer.net.created").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void beginAndEndMutation_shouldTrackInFlightGaugeUpAndDown() {
        customerMetrics.beginMutation();
        customerMetrics.beginMutation();

        assertThat(registry.get("customer.mutations.inflight").gauge().value()).isEqualTo(2.0);

        customerMetrics.endMutation();

        assertThat(registry.get("customer.mutations.inflight").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void repositoryCountGauge_shouldReflectTheLiveRepositoryCount() {
        when(customerRepository.count()).thenReturn(42L);

        // the async gauge is re-evaluated on every read, not cached from construction time
        assertThat(registry.get("customer.repository.count").gauge().value()).isEqualTo(42.0);
    }
}
