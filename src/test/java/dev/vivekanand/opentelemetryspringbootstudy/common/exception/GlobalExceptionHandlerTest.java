package dev.vivekanand.opentelemetryspringbootstudy.common.exception;

import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.CustomerNotFoundException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.DuplicateEmailException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.metrics.CustomerMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final CustomerMetrics customerMetrics = mock(CustomerMetrics.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(customerMetrics);

    @Test
    void handleNotFound_shouldReturn404WithMessageAndPath() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/customers/99");

        ResponseEntity<ApiError> response = handler.handleNotFound(new CustomerNotFoundException(99L), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().message()).isEqualTo("Customer not found with id: 99");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/customers/99");
        assertThat(response.getBody().details()).isEmpty();
    }

    @Test
    void handleDuplicate_shouldReturn409WithMessage() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/customers");

        ResponseEntity<ApiError> response = handler.handleDuplicate(new DuplicateEmailException("dup@example.com"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Customer already exists with email: dup@example.com");
    }

    @Test
    void handleValidation_shouldReturn400WithFieldErrorDetails() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/customers");

        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(
                new FieldError("customerRequest", "email", "email must be a valid email address")
        ));

        ResponseEntity<ApiError> response = handler.handleValidation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Validation failed");
        assertThat(response.getBody().details()).containsExactly("email: email must be a valid email address");
        verify(customerMetrics).recordValidationFailure(1);
    }

    @Test
    void handleUnexpected_shouldReturn500WithGenericMessage_hidingTheRealExceptionDetail() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/customers");

        ResponseEntity<ApiError> response = handler.handleUnexpected(
                new IllegalStateException("password=hunter2; connection to db-primary.internal refused"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().message()).doesNotContain("password", "db-primary.internal");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/customers");
    }
}
