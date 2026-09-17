package dev.vivekanand.opentelemetryspringbootstudy.config;

import dev.vivekanand.opentelemetryspringbootstudy.customer.entity.Customer;
import dev.vivekanand.opentelemetryspringbootstudy.customer.repository.CustomerRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("seed")
public class CustomerSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CustomerSeeder.class);

    private static final int CUSTOMER_COUNT = 100;

    private static final List<String> FIRST_NAMES = List.of(
            "James", "Mary", "Robert", "Patricia", "John", "Jennifer", "Michael", "Linda",
            "William", "Elizabeth", "David", "Barbara", "Richard", "Susan", "Joseph", "Jessica",
            "Thomas", "Sarah", "Charles", "Karen"
    );

    private static final List<String> LAST_NAMES = List.of(
            "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
            "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson",
            "Thomas", "Taylor", "Moore", "Jackson", "Martin"
    );

    private final CustomerRepository customerRepository;
    private final ObservationRegistry observationRegistry;

    public CustomerSeeder(CustomerRepository customerRepository, ObservationRegistry observationRegistry) {
        this.customerRepository = customerRepository;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public void run(String... args) {
        long existingCount = customerRepository.count();
        if (existingCount > 0) {
            log.atInfo()
                    .addKeyValue("existingCount", existingCount)
                    .log("Skipping customer seeding: customers already present");
            return;
        }

        // custom span: this runs at startup with no HTTP request to attach to, so it needs its own root span
        // rather than showing up as untraced background work
        Observation.createNotStarted("customer.seed", observationRegistry).observe(() -> {
            for (int i = 1; i <= CUSTOMER_COUNT; i++) {
                String firstName = FIRST_NAMES.get(i % FIRST_NAMES.size());
                String lastName = LAST_NAMES.get((i * 3) % LAST_NAMES.size());
                String email = "%s.%s.%d@example.com".formatted(firstName.toLowerCase(), lastName.toLowerCase(), i);
                String phone = "+1 555-%04d".formatted(i);
                customerRepository.save(new Customer(firstName, lastName, email, phone));
            }

            log.atInfo()
                    .addKeyValue("seededCount", CUSTOMER_COUNT)
                    .log("Seeded dummy customers");
        });
    }
}
