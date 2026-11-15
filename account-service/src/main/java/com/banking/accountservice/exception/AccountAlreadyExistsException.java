package com.banking.accountservice.exception;

public class AccountAlreadyExistsException extends BankingException{

    public AccountAlreadyExistsException(String email){
        super("Account with email " + email + " already exists.");
    }
}
