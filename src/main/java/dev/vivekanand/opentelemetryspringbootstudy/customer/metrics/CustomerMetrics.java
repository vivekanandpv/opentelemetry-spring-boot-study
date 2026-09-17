package dev.vivekanand.opentelemetryspringbootstudy.customer.metrics;

import dev.vivekanand.opentelemetryspringbootstudy.customer.repository.CustomerRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// registers every custom meter once, here, instead of scattering meterRegistry calls with
// hand-typed names across the classes that trigger them; see docs/custom-metrics.md for the
// full reasoning behind each meter kind chosen
@Component
public class CustomerMetrics {

    private static final int RECENT_BUFFER_CAPACITY = 50;

    private final Counter duplicateEmailCounter;
    private final AtomicLong validationErrorCount = new AtomicLong();
    private final Deque<Long> recentCustomerIds = new ConcurrentLinkedDeque<>();
    private final AtomicInteger inFlightMutations = new AtomicInteger();
    private final AtomicLong createdCount = new AtomicLong();
    private final AtomicLong deletedCount = new AtomicLong();
    private final DistributionSummary validationViolationsSummary;

    public CustomerMetrics(MeterRegistry registry, CustomerRepository customerRepository) {
        // Counter: a plain monotonic tally, incremented inline at the exact point the event happens
        this.duplicateEmailCounter = Counter.builder("customer.email.duplicate")
                .description("Number of create/update requests rejected for a duplicate email")
                .register(registry);

        // Async Counter (FunctionCounter): observes an existing monotonic tally rather than being told to increment
        FunctionCounter.builder("customer.validation.errors", validationErrorCount, AtomicLong::get)
                .description("Number of requests rejected by bean validation")
                .register(registry);

        // Gauge: reads app-maintained state, a bounded buffer updated as a side effect of create(), not queried fresh
        Gauge.builder("customer.recent.buffer.size", recentCustomerIds, Deque::size)
                .description("Size of the in-memory buffer of recently created customer ids")
                .register(registry);

        // Async Gauge: computed fresh from the database on every observation, exists purely to be measured
        Gauge.builder("customer.repository.count", customerRepository, CustomerRepository::count)
                .description("Current total row count in the customers table")
                .register(registry);

        // UpDownCounter: Micrometer has no dedicated type for this; the standard idiom is a Gauge over an
        // AtomicInteger the app increments/decrements directly around each operation's lifecycle
        Gauge.builder("customer.mutations.inflight", inFlightMutations, AtomicInteger::get)
                .description("Number of create/update/delete calls currently in progress")
                .register(registry);

        // Async UpDownCounter: same Gauge-over-atomic idiom, but the value is derived from two independently
        // tracked tallies rather than one counter bumped up and down directly
        Gauge.builder("customer.net.created", this, CustomerMetrics::netCreated)
                .description("Net customers created minus deleted through the API since this process started")
                .register(registry);

        // Histogram: DistributionSummary records a distribution of arbitrary values, not durations
        this.validationViolationsSummary = DistributionSummary.builder("customer.validation.violations")
                .description("Distribution of how many fields fail validation per rejected request")
                .register(registry);
    }

    public void recordDuplicateEmailConflict() {
        duplicateEmailCounter.increment();
    }

    public void recordValidationFailure(int violationCount) {
        validationErrorCount.incrementAndGet();
        validationViolationsSummary.record(violationCount);
    }

    public void recordCustomerCreated(Long customerId) {
        createdCount.incrementAndGet();
        recentCustomerIds.addLast(customerId);
        while (recentCustomerIds.size() > RECENT_BUFFER_CAPACITY) {
            recentCustomerIds.pollFirst();
        }
    }

    public void recordCustomerDeleted() {
        deletedCount.incrementAndGet();
    }

    public void beginMutation() {
        inFlightMutations.incrementAndGet();
    }

    public void endMutation() {
        inFlightMutations.decrementAndGet();
    }

    private double netCreated() {
        return createdCount.get() - deletedCount.get();
    }
}
