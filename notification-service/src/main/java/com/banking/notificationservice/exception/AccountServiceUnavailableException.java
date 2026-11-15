package com.banking.notificationservice.exception;

/**
 * Account Service unreachable: retry.
 */
public class AccountServiceUnavailableException extends NotificationException {
    public AccountServiceUnavailableException(Throwable cause) {
        super("Account service is unavailable", cause);
    }
}
