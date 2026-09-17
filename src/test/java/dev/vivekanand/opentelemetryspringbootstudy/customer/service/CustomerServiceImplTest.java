package dev.vivekanand.opentelemetryspringbootstudy.customer.service;

import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerRequest;
import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerResponse;
import dev.vivekanand.opentelemetryspringbootstudy.customer.entity.Customer;
import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.CustomerNotFoundException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.DuplicateEmailException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.mapper.CustomerMapper;
import dev.vivekanand.opentelemetryspringbootstudy.customer.repository.CustomerRepository;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static dev.vivekanand.opentelemetryspringbootstudy.customer.CustomerTestFixtures.customer;
import static dev.vivekanand.opentelemetryspringbootstudy.customer.CustomerTestFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CustomerMapper customerMapper;

    private CustomerServiceImpl customerService;
    private TestObservationRegistry observationRegistry;

    @BeforeEach
    void setUp() {
        // records real observations instead of discarding them, so span name/tag/error behavior is actually assertable
        observationRegistry = TestObservationRegistry.create();
        customerService = new CustomerServiceImpl(customerRepository, customerMapper, observationRegistry);
    }

    @Test
    void create_shouldSaveAndReturnResponse_whenEmailIsNotDuplicate() {
        CustomerRequest request = request("new@example.com");
        Customer toSave = customer(null, "new@example.com");
        Customer saved = customer(1L, "new@example.com");
        CustomerResponse expectedResponse = new CustomerResponse(1L, "John", "Doe", "new@example.com", "+1 555-0100", null, null);

        when(customerRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(customerMapper.toEntity(request)).thenReturn(toSave);
        when(customerRepository.save(toSave)).thenReturn(saved);
        when(customerMapper.toResponse(saved)).thenReturn(expectedResponse);

        CustomerResponse actual = customerService.create(request);

        assertThat(actual).isEqualTo(expectedResponse);
        verify(customerRepository).save(toSave);
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("customer.create")
                .that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasHighCardinalityKeyValue("customer.id", "1");
    }

    @Test
    void create_shouldThrowDuplicateEmailException_whenEmailAlreadyExists() {
        CustomerRequest request = request("existing@example.com");
        when(customerRepository.existsByEmail("existing@example.com")).thenReturn(true);

        assertThatThrownBy(() -> customerService.create(request))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessageContaining("existing@example.com");

        verify(customerRepository, never()).save(any());
        // confirms the span is marked errored rather than silently swallowing the failure
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("customer.create")
                .that()
                .hasError();
    }

    @Test
    void getById_shouldReturnResponse_whenCustomerExists() {
        Customer customer = customer(1L, "found@example.com");
        CustomerResponse expectedResponse = new CustomerResponse(1L, "John", "Doe", "found@example.com", "+1 555-0100", null, null);

        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(customerMapper.toResponse(customer)).thenReturn(expectedResponse);

        CustomerResponse actual = customerService.getById(1L);

        assertThat(actual).isEqualTo(expectedResponse);
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("customer.get-by-id")
                .that()
                .hasHighCardinalityKeyValue("customer.id", "1");
    }

    @Test
    void getById_shouldThrowNotFoundException_whenCustomerMissing() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.getById(99L))
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void getAll_shouldReturnMappedListOfAllCustomers() {
        Customer first = customer(1L, "first@example.com");
        Customer second = customer(2L, "second@example.com");
        CustomerResponse firstResponse = new CustomerResponse(1L, "John", "Doe", "first@example.com", "+1 555-0100", null, null);
        CustomerResponse secondResponse = new CustomerResponse(2L, "John", "Doe", "second@example.com", "+1 555-0100", null, null);

        when(customerRepository.findAll()).thenReturn(List.of(first, second));
        when(customerMapper.toResponse(first)).thenReturn(firstResponse);
        when(customerMapper.toResponse(second)).thenReturn(secondResponse);

        List<CustomerResponse> actual = customerService.getAll();

        assertThat(actual).containsExactly(firstResponse, secondResponse);
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("customer.get-all")
                .that()
                .hasHighCardinalityKeyValue("customer.count", "2");
    }

    @Test
    void getAll_shouldReturnEmptyList_whenNoCustomersExist() {
        when(customerRepository.findAll()).thenReturn(List.of());

        List<CustomerResponse> actual = customerService.getAll();

        assertThat(actual).isEmpty();
    }

    @Test
    void update_shouldUpdateAndReturnResponse_whenCustomerExistsAndEmailUnchanged() {
        Customer existing = customer(1L, "same@example.com");
        CustomerRequest request = request("Updated", "Name", "same@example.com", "+1 555-0200");
        CustomerResponse expectedResponse = new CustomerResponse(1L, "Updated", "Name", "same@example.com", "+1 555-0200", null, null);

        when(customerRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customerRepository.findByEmail("same@example.com")).thenReturn(Optional.of(existing));
        when(customerMapper.toResponse(existing)).thenReturn(expectedResponse);

        CustomerResponse actual = customerService.update(1L, request);

        assertThat(actual).isEqualTo(expectedResponse);
        verify(customerMapper).updateEntity(existing, request);
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("customer.update")
                .that()
                .hasHighCardinalityKeyValue("customer.id", "1");
    }

    @Test
    void update_shouldThrowNotFoundException_whenCustomerMissing() {
        CustomerRequest request = request("someone@example.com");
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.update(1L, request))
                .isInstanceOf(CustomerNotFoundException.class);

        verify(customerMapper, never()).updateEntity(any(), any());
    }

    @Test
    void update_shouldThrowDuplicateEmailException_whenEmailBelongsToAnotherCustomer() {
        Customer existing = customer(1L, "mine@example.com");
        Customer other = customer(2L, "taken@example.com");
        CustomerRequest request = request("taken@example.com");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customerRepository.findByEmail("taken@example.com")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> customerService.update(1L, request))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessageContaining("taken@example.com");

        verify(customerMapper, never()).updateEntity(any(), any());
    }

    @Test
    void update_shouldSucceed_whenNoOtherCustomerHasTheEmail() {
        Customer existing = customer(1L, "mine@example.com");
        CustomerRequest request = request("brandnew@example.com");
        CustomerResponse expectedResponse = new CustomerResponse(1L, "John", "Doe", "brandnew@example.com", "+1 555-0100", null, null);

        when(customerRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customerRepository.findByEmail("brandnew@example.com")).thenReturn(Optional.empty());
        when(customerMapper.toResponse(existing)).thenReturn(expectedResponse);

        CustomerResponse actual = customerService.update(1L, request);

        assertThat(actual).isEqualTo(expectedResponse);
        verify(customerMapper).updateEntity(existing, request);
    }

    @Test
    void delete_shouldRemoveCustomer_whenCustomerExists() {
        Customer existing = customer(1L, "todelete@example.com");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(existing));

        customerService.delete(1L);

        verify(customerRepository, times(1)).delete(existing);
        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("customer.delete")
                .that()
                .hasHighCardinalityKeyValue("customer.id", "1");
    }

    @Test
    void delete_shouldThrowNotFoundException_whenCustomerMissing() {
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.delete(1L))
                .isInstanceOf(CustomerNotFoundException.class);

        verify(customerRepository, never()).delete(any());
    }
}
