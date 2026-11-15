package com.banking.paymentservice.exception;

public class InvalidWebhookException extends PaymentException {
    public InvalidWebhookException(String message) { super(message); }
}
