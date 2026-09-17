package dev.vivekanand.opentelemetryspringbootstudy.customer.mapper;

import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerRequest;
import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerResponse;
import dev.vivekanand.opentelemetryspringbootstudy.customer.entity.Customer;
import org.springframework.stereotype.Component;

@Component
public class CustomerMapper {

    public Customer toEntity(CustomerRequest request) {
        return new Customer(request.firstName(), request.lastName(), request.email(), request.phone());
    }

    public void updateEntity(Customer customer, CustomerRequest request) {
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setEmail(request.email());
        customer.setPhone(request.phone());
    }

    public CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getCreatedAt(),
                customer.getUpdatedAt()
        );
    }
}
