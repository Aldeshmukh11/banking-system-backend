package com.banking.paymentservice.exception;

public abstract class PaymentException extends RuntimeException {
    protected PaymentException(String message) { super(message); }
    protected PaymentException(String message, Throwable cause) { super(message, cause); }
}
