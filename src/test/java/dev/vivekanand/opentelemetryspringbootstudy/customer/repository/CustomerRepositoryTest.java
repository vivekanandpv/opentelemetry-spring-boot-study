package dev.vivekanand.opentelemetryspringbootstudy.customer.repository;

import dev.vivekanand.opentelemetryspringbootstudy.customer.entity.Customer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class CustomerRepositoryTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void save_shouldPersistCustomerAndPopulateGeneratedFields() {
        Customer customer = new Customer("Jane", "Smith", "jane@example.com", "+1 555-0101");

        Customer saved = customerRepository.saveAndFlush(customer);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findByEmail_shouldReturnCustomer_whenEmailExists() {
        customerRepository.saveAndFlush(new Customer("Jane", "Smith", "jane@example.com", "+1 555-0101"));

        Optional<Customer> found = customerRepository.findByEmail("jane@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getFirstName()).isEqualTo("Jane");
    }

    @Test
    void findByEmail_shouldReturnEmpty_whenEmailDoesNotExist() {
        Optional<Customer> found = customerRepository.findByEmail("missing@example.com");

        assertThat(found).isEmpty();
    }

    @Test
    void existsByEmail_shouldReturnTrue_whenEmailExists() {
        customerRepository.saveAndFlush(new Customer("Jane", "Smith", "jane@example.com", "+1 555-0101"));

        assertThat(customerRepository.existsByEmail("jane@example.com")).isTrue();
        assertThat(customerRepository.existsByEmail("nope@example.com")).isFalse();
    }

    @Test
    void save_shouldThrowDataIntegrityViolationException_whenEmailIsDuplicated() {
        customerRepository.saveAndFlush(new Customer("Jane", "Smith", "dup@example.com", "+1 555-0101"));

        assertThatThrownBy(() ->
                customerRepository.saveAndFlush(new Customer("Other", "Person", "dup@example.com", "+1 555-0102")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
