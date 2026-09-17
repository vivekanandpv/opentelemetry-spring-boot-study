package dev.vivekanand.opentelemetryspringbootstudy.config;

import dev.vivekanand.opentelemetryspringbootstudy.customer.entity.Customer;
import dev.vivekanand.opentelemetryspringbootstudy.customer.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerSeederTest {

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private CustomerSeeder customerSeeder;

    @Test
    void run_shouldSeedExactlyOneHundredCustomers_whenRepositoryIsEmpty() {
        when(customerRepository.count()).thenReturn(0L);

        customerSeeder.run();

        verify(customerRepository, times(100)).save(any(Customer.class));
    }

    @Test
    void run_shouldSkipSeeding_whenCustomersAlreadyExist() {
        when(customerRepository.count()).thenReturn(5L);

        customerSeeder.run();

        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void run_shouldGenerateDistinctEmailForEverySeededCustomer() {
        when(customerRepository.count()).thenReturn(0L);
        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);

        customerSeeder.run();

        verify(customerRepository, times(100)).save(captor.capture());
        Set<String> distinctEmails = captor.getAllValues().stream()
                .map(Customer::getEmail)
                .collect(Collectors.toSet());
        assertThat(distinctEmails).hasSize(100);
    }

    @Test
    void run_shouldGenerateValidNamesAndPhoneForEverySeededCustomer() {
        when(customerRepository.count()).thenReturn(0L);
        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);

        customerSeeder.run();

        verify(customerRepository, times(100)).save(captor.capture());
        List<Customer> seeded = captor.getAllValues();
        assertThat(seeded).allSatisfy(customer -> {
            assertThat(customer.getFirstName()).isNotBlank();
            assertThat(customer.getLastName()).isNotBlank();
            assertThat(customer.getEmail()).matches("^[a-z]+\\.[a-z]+\\.\\d+@example\\.com$");
            assertThat(customer.getPhone()).matches("^\\+1 555-\\d{4}$");
        });
    }
}
