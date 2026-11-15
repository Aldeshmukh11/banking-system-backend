package com.banking.transactionservice.exception;

public class AccountNotFoundException extends BankingException {
    public AccountNotFoundException(String accountNumber) {
        super("Account not found for: " + accountNumber);
    }
}
