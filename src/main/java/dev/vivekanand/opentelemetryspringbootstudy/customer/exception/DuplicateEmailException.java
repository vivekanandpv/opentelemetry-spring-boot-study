package dev.vivekanand.opentelemetryspringbootstudy.customer.exception;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("Customer already exists with email: " + email);
    }
}
