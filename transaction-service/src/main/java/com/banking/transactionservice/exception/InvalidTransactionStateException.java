package com.banking.transactionservice.exception;

public class InvalidTransactionStateException extends BankingException {
    public InvalidTransactionStateException(String message) {
        super(message);
    }
}
