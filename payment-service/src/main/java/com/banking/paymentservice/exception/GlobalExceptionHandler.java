package com.banking.paymentservice.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<ExceptionResponse> badRequest(InvalidAmountException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());              // 400
    }

    @ExceptionHandler(InvalidWebhookException.class)
    public ResponseEntity<ExceptionResponse> badWebhook(InvalidWebhookException ex) {
        log.warn("Rejected webhook: {}", ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage());             // 401
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ExceptionResponse> notFound(PaymentNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());                // 404 → Razorpay retries
    }

    @ExceptionHandler(PaymentOrderCreationException.class)
    public ResponseEntity<ExceptionResponse> gateway(PaymentOrderCreationException ex) {
        log.error("Razorpay order creation failed", ex.getCause());
        return build(HttpStatus.BAD_GATEWAY, ex.getMessage());              // 502
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ExceptionResponse> validation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ExceptionResponse> unexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");
    }

    private ResponseEntity<ExceptionResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ExceptionResponse(status.value(), status.getReasonPhrase(), message));
    }
}
