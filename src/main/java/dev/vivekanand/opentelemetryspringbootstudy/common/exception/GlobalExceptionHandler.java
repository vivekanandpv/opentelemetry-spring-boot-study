package dev.vivekanand.opentelemetryspringbootstudy.common.exception;

import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.CustomerNotFoundException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.exception.DuplicateEmailException;
import dev.vivekanand.opentelemetryspringbootstudy.customer.metrics.CustomerMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final CustomerMetrics customerMetrics;

    public GlobalExceptionHandler(CustomerMetrics customerMetrics) {
        this.customerMetrics = customerMetrics;
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(CustomerNotFoundException ex, HttpServletRequest request) {
        log.atDebug()
                .addKeyValue("path", request.getRequestURI())
                .log(ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateEmailException ex, HttpServletRequest request) {
        log.atDebug()
                .addKeyValue("path", request.getRequestURI())
                .log(ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .toList();
        log.atDebug()
                .addKeyValue("path", request.getRequestURI())
                .addKeyValue("violationCount", details.size())
                .log("Validation failed");
        customerMetrics.recordValidationFailure(details.size()); // async Counter tally + Histogram of violations-per-request
        return build(HttpStatus.BAD_REQUEST, "Validation failed", request, details);
    }

    // catches anything not explicitly handled above; without this, an unexpected failure would
    // fall through to Spring's default error handling with no log entry of ours pointing at it
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.atError()
                .addKeyValue("path", request.getRequestURI())
                .addKeyValue("exceptionType", ex.getClass().getName())
                .setCause(ex)
                .log("Unhandled exception while processing request");
        // the client gets a generic message; ex.getMessage() may contain internals (SQL, stack detail) that shouldn't leave the service
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, List.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request, List<String> details) {
        ApiError apiError = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                details
        );
        return ResponseEntity.status(status).body(apiError);
    }
}
