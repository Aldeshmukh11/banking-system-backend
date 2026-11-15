package com.banking.transactionservice.exception;

public class PendingVerificationException extends BankingException {
    public PendingVerificationException() {
        super("You have a transaction awaiting OTP verification. Complete it before starting a new transfer.");
    }
}