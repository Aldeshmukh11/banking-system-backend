package com.banking.accountservice.exception;

public class AccountInactiveException extends BankingException{

    public AccountInactiveException(String accountNumber){
        super("Account is not active for: " + accountNumber);
    }
}
