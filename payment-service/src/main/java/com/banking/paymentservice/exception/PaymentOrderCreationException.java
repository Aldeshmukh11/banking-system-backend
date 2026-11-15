package com.banking.paymentservice.exception;

public class PaymentOrderCreationException extends PaymentException {
    public PaymentOrderCreationException(Throwable cause) {
        super("Could not create payment order. Please try again.", cause);
    }
}
