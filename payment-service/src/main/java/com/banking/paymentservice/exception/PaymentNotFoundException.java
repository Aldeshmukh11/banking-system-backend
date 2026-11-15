package com.banking.paymentservice.exception;

public class PaymentNotFoundException extends PaymentException {
    public PaymentNotFoundException(String orderId) {
        super("Payment not found for order: " + orderId);
    }
}
