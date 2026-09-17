package dev.vivekanand.opentelemetryspringbootstudy.customer.service;

import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerRequest;
import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerResponse;

import java.util.List;

public interface CustomerService {

    CustomerResponse create(CustomerRequest request);

    CustomerResponse getById(Long id);

    List<CustomerResponse> getAll();

    CustomerResponse update(Long id, CustomerRequest request);

    void delete(Long id);
}
