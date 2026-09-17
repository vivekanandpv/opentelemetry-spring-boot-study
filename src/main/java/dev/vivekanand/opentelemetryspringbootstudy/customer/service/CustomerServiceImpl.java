package dev.vivekanand.opentelemetryspringbootstudy.customer.service;

import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerRequest;
import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerResponse;
import dev.vivekanand.opentelemetryspringbootstudy.customer.entity.Customer;
import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.CustomerNotFoundException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.DuplicateEmailException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.mapper.CustomerMapper;
import dev.vivekanand.opentelemetryspringbootstudy.customer.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;

    public CustomerServiceImpl(CustomerRepository customerRepository, CustomerMapper customerMapper) {
        this.customerRepository = customerRepository;
        this.customerMapper = customerMapper;
    }

    @Override
    @Transactional
    public CustomerResponse create(CustomerRequest request) {
        if (customerRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }
        Customer customer = customerMapper.toEntity(request);
        return customerMapper.toResponse(customerRepository.save(customer));
    }

    @Override
    public CustomerResponse getById(Long id) {
        return customerMapper.toResponse(findCustomerOrThrow(id));
    }

    @Override
    public List<CustomerResponse> getAll() {
        return customerRepository.findAll().stream()
                .map(customerMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public CustomerResponse update(Long id, CustomerRequest request) {
        Customer customer = findCustomerOrThrow(id);
        customerRepository.findByEmail(request.email())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new DuplicateEmailException(request.email());
                });
        customerMapper.updateEntity(customer, request);
        return customerMapper.toResponse(customer);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        customerRepository.delete(findCustomerOrThrow(id));
    }

    private Customer findCustomerOrThrow(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
    }
}
