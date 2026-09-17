package dev.vivekanand.opentelemetryspringbootstudy.customer.service;

import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerRequest;
import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerResponse;
import dev.vivekanand.opentelemetryspringbootstudy.customer.entity.Customer;
import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.CustomerNotFoundException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.DuplicateEmailException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.mapper.CustomerMapper;
import dev.vivekanand.opentelemetryspringbootstudy.customer.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class CustomerServiceImpl implements CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerServiceImpl.class);

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
        CustomerResponse response = customerMapper.toResponse(customerRepository.save(customer));
        log.atInfo()
                .addKeyValue("customerId", response.id())
                .log("Customer created");
        return response;
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
        log.atInfo()
                .addKeyValue("customerId", id)
                .log("Customer updated");
        return customerMapper.toResponse(customer);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        customerRepository.delete(findCustomerOrThrow(id));
        log.atInfo()
                .addKeyValue("customerId", id)
                .log("Customer deleted");
    }

    private Customer findCustomerOrThrow(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
    }
}
