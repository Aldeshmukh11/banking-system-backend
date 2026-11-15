package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/accounts")
@Slf4j
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request){
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.createAccount(request));
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccount(
            @PathVariable String accountNumber){
        return ResponseEntity.ok(accountService.getAccount(accountNumber));
    }

    @GetMapping("/{accountNumber}/balance-email")
    public ResponseEntity<Map<String,Object>> getCustomerEmailAndBalance(
            @PathVariable String accountNumber){
        Map<String, Object> map =  accountService.getCustomerEmailAndBalance(accountNumber);
        return ResponseEntity.ok(map);
    }

    @GetMapping("/{accountNumber}/balance")
    public ResponseEntity<BigDecimal> getBalance(
            @PathVariable String accountNumber){
        return ResponseEntity.ok(accountService.getBalance(accountNumber));
    }

    @PutMapping("/{accountNumber}/block")
    public ResponseEntity<String> blockAccount(
            @PathVariable String accountNumber){
        accountService.blockAccount(accountNumber);
        return ResponseEntity.ok("Account blocked successfully");
    }

    /**
     * SAGA STEP 1 - Deduct balance
     * Called by Transaction Service when transfer is initiated.
     */

    @PutMapping("/{accountNumber}/deduct")
    public ResponseEntity<String> deductBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount,
            @RequestParam String transactionId){
        accountService.deductBalance(transactionId,accountNumber, amount);
        return ResponseEntity.ok("Balance deducted successfully");
    }


    /**
     * SAGA STEP 4 - Compensating transaction endpoint
     * CALLED BY TRANSACTION SERVICE in TWO SCENARIOS:
     * 1. Fraud detected -> refund sender (undo step 1)
     * 2. Transaction completed -> credit receiver
     */

    @PutMapping("/{accountNumber}/credit")
    public ResponseEntity<String> creditBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount,
            @RequestParam String transactionId){
        accountService.creditBalance(transactionId,accountNumber, amount);
        return ResponseEntity.ok("BALANCE CREDITED SUCCESSFULLY");
    }

    @PutMapping("/{accountNumber}/refund")
    public ResponseEntity<String> refundBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount,
            @RequestParam String transactionId){
        accountService.refundBalance(transactionId,accountNumber, amount);
        return ResponseEntity.ok("BALANCE REFUNDED SUCCESSFULLY");
    }

    @GetMapping("/{accountNumber}/status")
    public ResponseEntity<String> getAccountStatus(
            @PathVariable String accountNumber){
        String status = accountService.getAccountStatus(accountNumber);
        return ResponseEntity.ok(status);
    }

}