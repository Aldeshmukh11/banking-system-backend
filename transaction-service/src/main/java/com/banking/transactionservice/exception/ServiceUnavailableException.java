package com.banking.transactionservice.exception;

public class ServiceUnavailableException extends BankingException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}