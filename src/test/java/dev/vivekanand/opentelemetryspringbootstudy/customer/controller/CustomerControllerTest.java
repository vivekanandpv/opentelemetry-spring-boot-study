package dev.vivekanand.opentelemetryspringbootstudy.customer.controller;

import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerRequest;
import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerResponse;
import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.CustomerNotFoundException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.DuplicateEmailException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerController.class)
class CustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CustomerService customerService;

    private static final Instant TIMESTAMP = Instant.parse("2024-01-01T00:00:00Z");

    @Test
    void create_shouldReturn201WithLocationHeader_whenRequestIsValid() throws Exception {
        CustomerRequest request = new CustomerRequest("Jane", "Smith", "jane@example.com", "+1 555-0101");
        CustomerResponse response = new CustomerResponse(1L, "Jane", "Smith", "jane@example.com", "+1 555-0101", TIMESTAMP, TIMESTAMP);
        when(customerService.create(any(CustomerRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/customers/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("jane@example.com"));
    }

    @Test
    void create_shouldReturn400WithValidationDetails_whenRequestIsInvalid() throws Exception {
        CustomerRequest invalidRequest = new CustomerRequest("", "Smith", "not-an-email", null);

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void create_shouldReturn409_whenEmailAlreadyExists() throws Exception {
        CustomerRequest request = new CustomerRequest("Jane", "Smith", "dup@example.com", "+1 555-0101");
        when(customerService.create(any(CustomerRequest.class)))
                .thenThrow(new DuplicateEmailException("dup@example.com"));

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Customer already exists with email: dup@example.com"));
    }

    @Test
    void getById_shouldReturn200WithCustomer_whenFound() throws Exception {
        CustomerResponse response = new CustomerResponse(1L, "Jane", "Smith", "jane@example.com", "+1 555-0101", TIMESTAMP, TIMESTAMP);
        when(customerService.getById(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/customers/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.firstName").value("Jane"));
    }

    @Test
    void getById_shouldReturn404_whenNotFound() throws Exception {
        when(customerService.getById(99L)).thenThrow(new CustomerNotFoundException(99L));

        mockMvc.perform(get("/api/v1/customers/{id}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Customer not found with id: 99"))
                .andExpect(jsonPath("$.path").value("/api/v1/customers/99"));
    }

    @Test
    void getAll_shouldReturn200WithListOfCustomers() throws Exception {
        CustomerResponse first = new CustomerResponse(1L, "Jane", "Smith", "jane@example.com", "+1 555-0101", TIMESTAMP, TIMESTAMP);
        CustomerResponse second = new CustomerResponse(2L, "John", "Doe", "john@example.com", "+1 555-0102", TIMESTAMP, TIMESTAMP);
        when(customerService.getAll()).thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].email").value("jane@example.com"))
                .andExpect(jsonPath("$[1].email").value("john@example.com"));
    }

    @Test
    void getAll_shouldReturn200WithEmptyList_whenNoCustomers() throws Exception {
        when(customerService.getAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void update_shouldReturn200WithUpdatedCustomer_whenValid() throws Exception {
        CustomerRequest request = new CustomerRequest("Jane", "Updated", "jane@example.com", "+1 555-0101");
        CustomerResponse response = new CustomerResponse(1L, "Jane", "Updated", "jane@example.com", "+1 555-0101", TIMESTAMP, TIMESTAMP);
        when(customerService.update(eq(1L), any(CustomerRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/v1/customers/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Updated"));
    }

    @Test
    void update_shouldReturn404_whenCustomerMissing() throws Exception {
        CustomerRequest request = new CustomerRequest("Jane", "Updated", "jane@example.com", "+1 555-0101");
        when(customerService.update(eq(99L), any(CustomerRequest.class)))
                .thenThrow(new CustomerNotFoundException(99L));

        mockMvc.perform(put("/api/v1/customers/{id}", 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_shouldReturn204_whenCustomerExists() throws Exception {
        mockMvc.perform(delete("/api/v1/customers/{id}", 1L))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_shouldReturn404_whenCustomerMissing() throws Exception {
        org.mockito.Mockito.doThrow(new CustomerNotFoundException(99L))
                .when(customerService).delete(99L);

        mockMvc.perform(delete("/api/v1/customers/{id}", 99L))
                .andExpect(status().isNotFound());
    }

    @Test
    void getById_shouldReturn500WithGenericMessage_whenAnUnexpectedExceptionEscapesTheService() throws Exception {
        when(customerService.getById(1L)).thenThrow(new IllegalStateException("db-primary.internal refused connection"));

        mockMvc.perform(get("/api/v1/customers/{id}", 1L))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("db-primary"))));
    }
}
