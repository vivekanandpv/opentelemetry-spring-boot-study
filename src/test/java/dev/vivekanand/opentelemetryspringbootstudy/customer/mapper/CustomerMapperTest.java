package dev.vivekanand.opentelemetryspringbootstudy.customer.mapper;

import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerRequest;
import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerResponse;
import dev.vivekanand.opentelemetryspringbootstudy.customer.entity.Customer;
import org.junit.jupiter.api.Test;

import static dev.vivekanand.opentelemetryspringbootstudy.customer.CustomerTestFixtures.customer;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerMapperTest {

    private final CustomerMapper mapper = new CustomerMapper();

    @Test
    void toEntity_shouldMapAllFieldsFromRequest() {
        CustomerRequest request = new CustomerRequest("Jane", "Smith", "jane.smith@example.com", "+1 555-0101");

        Customer customer = mapper.toEntity(request);

        assertThat(customer.getFirstName()).isEqualTo("Jane");
        assertThat(customer.getLastName()).isEqualTo("Smith");
        assertThat(customer.getEmail()).isEqualTo("jane.smith@example.com");
        assertThat(customer.getPhone()).isEqualTo("+1 555-0101");
        assertThat(customer.getId()).isNull();
    }

    @Test
    void updateEntity_shouldOverwriteMutableFieldsOnExistingEntity() {
        Customer customer = customer(1L, "old@example.com");
        CustomerRequest request = new CustomerRequest("Updated", "Name", "new@example.com", "+1 555-0199");

        mapper.updateEntity(customer, request);

        assertThat(customer.getId()).isEqualTo(1L);
        assertThat(customer.getFirstName()).isEqualTo("Updated");
        assertThat(customer.getLastName()).isEqualTo("Name");
        assertThat(customer.getEmail()).isEqualTo("new@example.com");
        assertThat(customer.getPhone()).isEqualTo("+1 555-0199");
    }

    @Test
    void toResponse_shouldMapAllFieldsIncludingAuditTimestamps() {
        Customer customer = customer(42L, "Alice", "Wonder", "alice@example.com", "+1 555-0102");

        CustomerResponse response = mapper.toResponse(customer);

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.firstName()).isEqualTo("Alice");
        assertThat(response.lastName()).isEqualTo("Wonder");
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.phone()).isEqualTo("+1 555-0102");
        assertThat(response.createdAt()).isEqualTo(customer.getCreatedAt());
        assertThat(response.updatedAt()).isEqualTo(customer.getUpdatedAt());
    }
}
