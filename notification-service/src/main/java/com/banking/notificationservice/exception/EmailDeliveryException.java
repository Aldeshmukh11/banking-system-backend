package com.banking.notificationservice.exception;

/**
 * SMTP failure: retry.
 */
public class EmailDeliveryException extends NotificationException {
    public EmailDeliveryException(Throwable cause) {
        super("Email delivery failed", cause);
    }
}
