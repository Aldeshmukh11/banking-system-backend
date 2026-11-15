package com.banking.accountservice.exception;

public class InvalidTransactionStateException extends BankingException{

    public InvalidTransactionStateException(String message){
        super(message);
    }
}
