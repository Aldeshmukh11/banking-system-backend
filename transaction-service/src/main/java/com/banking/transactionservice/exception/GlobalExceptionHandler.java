package com.banking.transactionservice.exception;

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

    @ExceptionHandler({InvalidAmountException.class, InvalidTransferException.class})
    public ResponseEntity<ExceptionResponse> handleBadRequest(BankingException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());                 // 400
    }

    @ExceptionHandler(AccountBlockedException.class)
    public ResponseEntity<ExceptionResponse> handleBlocked(AccountBlockedException ex) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage());                   // 403
    }

    @ExceptionHandler({AccountNotFoundException.class, TransactionNotFoundException.class})
    public ResponseEntity<ExceptionResponse> handleNotFound(BankingException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());                   // 404
    }

    @ExceptionHandler({PendingVerificationException.class, InvalidTransactionStateException.class})
    public ResponseEntity<ExceptionResponse> handleConflict(BankingException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());                    // 409
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ExceptionResponse> handleUnavailable(ServiceUnavailableException ex) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());         // 503
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ExceptionResponse> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ExceptionResponse> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");
    }

    private ResponseEntity<ExceptionResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ExceptionResponse(status.value(), status.getReasonPhrase(), message));
    }
}