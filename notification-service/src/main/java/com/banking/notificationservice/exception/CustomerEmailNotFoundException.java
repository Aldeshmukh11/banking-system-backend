package com.banking.notificationservice.exception;

/**
 * Account doesn't exist or has no email: retrying can't fix it.
 */
public class CustomerEmailNotFoundException extends NotificationException {
    public CustomerEmailNotFoundException(String accountNumber) {
        super("No email found for account: " + accountNumber);
    }
}
