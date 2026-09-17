package dev.vivekanand.opentelemetryspringbootstudy.customer;

import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerRequest;
import dev.vivekanand.opentelemetryspringbootstudy.customer.entity.Customer;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

public final class CustomerTestFixtures {

    public static final Instant FIXED_INSTANT = Instant.parse("2024-01-01T00:00:00Z");

    private CustomerTestFixtures() {
    }

    public static Customer customer(Long id, String firstName, String lastName, String email, String phone) {
        Customer customer = new Customer(firstName, lastName, email, phone);
        if (id != null) {
            ReflectionTestUtils.setField(customer, "id", id);
        }
        ReflectionTestUtils.setField(customer, "createdAt", FIXED_INSTANT);
        ReflectionTestUtils.setField(customer, "updatedAt", FIXED_INSTANT);
        return customer;
    }

    public static Customer customer(Long id, String email) {
        return customer(id, "John", "Doe", email, "+1 555-0100");
    }

    public static CustomerRequest request(String firstName, String lastName, String email, String phone) {
        return new CustomerRequest(firstName, lastName, email, phone);
    }

    public static CustomerRequest request(String email) {
        return request("John", "Doe", email, "+1 555-0100");
    }
}
