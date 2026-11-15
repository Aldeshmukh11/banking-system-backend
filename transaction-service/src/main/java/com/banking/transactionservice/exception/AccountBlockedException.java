package com.banking.transactionservice.exception;

public class AccountBlockedException extends BankingException {
    public AccountBlockedException(String accountNumber) {
        super("Account " + accountNumber + " is blocked. Kindly contact the bank.");
    }
}