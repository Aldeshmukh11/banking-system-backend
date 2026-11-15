package com.banking.accountservice.exception;

public abstract class BankingException extends RuntimeException{

    public BankingException(String message){
        super(message);
    }

}
