package com.banking.accountservice.exception;

public class InsufficientFundsException extends BankingException{

    public InsufficientFundsException(String accountNumber){
        super("Insufficient funds in account: " + accountNumber);
    }
}
