package com.banking.transactionservice.exception;

public class TransactionNotFoundException extends BankingException {
    public TransactionNotFoundException(String transactionId) {
        super("Transaction not found: " + transactionId);
    }
}