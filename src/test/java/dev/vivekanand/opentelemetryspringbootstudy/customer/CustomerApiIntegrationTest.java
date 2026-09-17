package dev.vivekanand.opentelemetryspringbootstudy.customer;

import dev.vivekanand.opentelemetryspringbootstudy.customer.dto.CustomerRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullCustomerLifecycle_createReadUpdateDelete() throws Exception {
        CustomerRequest createRequest = new CustomerRequest("Jane", "Smith", "jane.lifecycle@example.com", "+1 555-0101");

        String createResponseJson = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.email").value("jane.lifecycle@example.com"))
                .andReturn().getResponse().getContentAsString();

        Long id = objectMapper.readTree(createResponseJson).get("id").asLong();

        mockMvc.perform(get("/api/v1/customers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Jane"));

        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());

        CustomerRequest updateRequest = new CustomerRequest("Jane", "Updated", "jane.lifecycle@example.com", "+1 555-0102");
        mockMvc.perform(put("/api/v1/customers/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Updated"))
                .andExpect(jsonPath("$.phone").value("+1 555-0102"));

        mockMvc.perform(delete("/api/v1/customers/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/customers/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_shouldReturn409_whenEmailAlreadyRegistered() throws Exception {
        CustomerRequest request = new CustomerRequest("Jane", "Smith", "duplicate@example.com", "+1 555-0101");

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Customer already exists with email: duplicate@example.com"));
    }

    @Test
    void create_shouldReturn400_whenPayloadIsInvalid() throws Exception {
        CustomerRequest invalidRequest = new CustomerRequest("", "", "not-an-email", "bad-phone!!");

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()").value(4));
    }

    @Test
    void update_shouldReturn404_whenCustomerDoesNotExist() throws Exception {
        CustomerRequest request = new CustomerRequest("Jane", "Smith", "ghost@example.com", "+1 555-0101");

        mockMvc.perform(put("/api/v1/customers/{id}", 999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_shouldReturn404_whenCustomerDoesNotExist() throws Exception {
        mockMvc.perform(delete("/api/v1/customers/{id}", 999_999L))
                .andExpect(status().isNotFound());
    }
}
