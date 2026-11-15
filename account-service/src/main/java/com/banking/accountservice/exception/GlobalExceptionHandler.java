package com.banking.accountservice.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler{

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ExceptionResponse> handleNotFound(AccountNotFoundException ex) {
        log.error("Invoked AccountNotFoundException handler-> {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage());                  // 404
    }

    @ExceptionHandler({AccountInactiveException.class, InsufficientFundsException.class})
    public ResponseEntity<ExceptionResponse> handleBusinessRule(BankingException ex) {
        return build(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());       // 422
    }

    @ExceptionHandler({InvalidTransactionStateException.class, AccountAlreadyExistsException.class})
    public ResponseEntity<ExceptionResponse> handleConflict(BankingException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());                   // 409
    }

    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<ExceptionResponse> handleBadRequest(InvalidAmountException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());                // 400
    }

    // @Valid failures on request bodies
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ExceptionResponse> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, msg);
    }

    // Anything unexpected: log the detail, don't leak it to the client
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
