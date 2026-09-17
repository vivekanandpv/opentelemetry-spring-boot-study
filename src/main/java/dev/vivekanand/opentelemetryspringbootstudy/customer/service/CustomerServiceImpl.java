package dev.vivekanand.opentelemetryspringbootstudy.customer.service;

import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerRequest;
import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerResponse;
import dev.vivekanand.opentelemetryspringbootstudy.customer.entity.Customer;
import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.CustomerNotFoundException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.DuplicateEmailException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.mapper.CustomerMapper;
import dev.vivekanand.opentelemetryspringbootstudy.customer.metrics.CustomerMetrics;
import dev.vivekanand.opentelemetryspringbootstudy.customer.repository.CustomerRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
    private final ObservationRegistry observationRegistry;
    private final CustomerMetrics customerMetrics;

    public CustomerServiceImpl(CustomerRepository customerRepository, CustomerMapper customerMapper,
                                ObservationRegistry observationRegistry, CustomerMetrics customerMetrics) {
        this.customerRepository = customerRepository;
        this.customerMapper = customerMapper;
        this.observationRegistry = observationRegistry;
        this.customerMetrics = customerMetrics;
    }

    @Override
    @Transactional
    public CustomerResponse create(CustomerRequest request) {
        // custom span: covers this business operation as its own unit of work, tagged with the id once it's known
        Observation observation = Observation.createNotStarted("customer.create", observationRegistry);
        customerMetrics.beginMutation(); // UpDownCounter: one more create/update/delete now in progress
        try {
            return observation.observe(() -> {
                if (customerRepository.existsByEmail(request.email())) {
                    customerMetrics.recordDuplicateEmailConflict(); // Counter: one more rejected-duplicate event
                    throw new DuplicateEmailException(request.email());
                }
                Customer customer = customerMapper.toEntity(request);
                CustomerResponse response = customerMapper.toResponse(customerRepository.save(customer));
                observation.highCardinalityKeyValue("customer.id", String.valueOf(response.id()));
                customerMetrics.recordCustomerCreated(response.id()); // Gauge buffer + async UpDownCounter tally, both updated here
                log.atInfo()
                        .addKeyValue("customerId", response.id())
                        .log("Customer created");
                return response;
            });
        } finally {
            customerMetrics.endMutation(); // UpDownCounter: this call is no longer in progress, success or failure
        }
    }

    @Override
    public CustomerResponse getById(Long id) {
        // custom span, borderline antipattern: one repo call, so duration ~= parent HTTP span; kept only so customer.id stays queryable on reads too, not just writes
        Observation observation = Observation.createNotStarted("customer.get-by-id", observationRegistry)
                .highCardinalityKeyValue("customer.id", String.valueOf(id));
        return observation.observe(() -> customerMapper.toResponse(findCustomerOrThrow(id)));
    }

    @Override
    public List<CustomerResponse> getAll() {
        // custom span, same borderline antipattern as getById: one repo call adds little beyond the parent span; kept for the customer.count tag and consistency with the other operations
        Observation observation = Observation.createNotStarted("customer.get-all", observationRegistry);
        return observation.observe(() -> {
            List<CustomerResponse> customers = customerRepository.findAll().stream()
                    .map(customerMapper::toResponse)
                    .toList();
            observation.highCardinalityKeyValue("customer.count", String.valueOf(customers.size()));
            return customers;
        });
    }

    @Override
    @Transactional
    public CustomerResponse update(Long id, CustomerRequest request) {
        // custom span: covers the lookup, duplicate check, and save as one unit, tagged with the id being updated
        Observation observation = Observation.createNotStarted("customer.update", observationRegistry)
                .highCardinalityKeyValue("customer.id", String.valueOf(id));
        customerMetrics.beginMutation(); // UpDownCounter: one more create/update/delete now in progress
        try {
            return observation.observe(() -> {
                Customer customer = findCustomerOrThrow(id);
                customerRepository.findByEmail(request.email())
                        .filter(existing -> !existing.getId().equals(id))
                        .ifPresent(existing -> {
                            customerMetrics.recordDuplicateEmailConflict(); // Counter: one more rejected-duplicate event
                            throw new DuplicateEmailException(request.email());
                        });
                customerMapper.updateEntity(customer, request);
                log.atInfo()
                        .addKeyValue("customerId", id)
                        .log("Customer updated");
                return customerMapper.toResponse(customer);
            });
        } finally {
            customerMetrics.endMutation(); // UpDownCounter: this call is no longer in progress, success or failure
        }
    }

    @Override
    @Transactional
    public void delete(Long id) {
        // custom span: covers the lookup and delete as one unit, tagged with the id being removed
        Observation observation = Observation.createNotStarted("customer.delete", observationRegistry)
                .highCardinalityKeyValue("customer.id", String.valueOf(id));
        customerMetrics.beginMutation(); // UpDownCounter: one more create/update/delete now in progress
        try {
            observation.observe(() -> {
                customerRepository.delete(findCustomerOrThrow(id));
                customerMetrics.recordCustomerDeleted(); // async UpDownCounter: net-created tally goes down by one
                log.atInfo()
                        .addKeyValue("customerId", id)
                        .log("Customer deleted");
            });
        } finally {
            customerMetrics.endMutation(); // UpDownCounter: this call is no longer in progress, success or failure
        }
    }

    private Customer findCustomerOrThrow(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException(id));
    }
}
