package com.banking.notificationservice.exception;

/**
 * Bad event: retrying can't fix it.
 */
public class InvalidNotificationPayloadException extends NotificationException {
    public InvalidNotificationPayloadException(String message) { super(message); }
}