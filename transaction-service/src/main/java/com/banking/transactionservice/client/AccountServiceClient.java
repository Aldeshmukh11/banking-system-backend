package com.banking.transactionservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@FeignClient(name = "account-service",
        url = "${account.service.url}",
        path = "/api/v1/accounts")
public interface AccountServiceClient {

    @GetMapping("/{accountNumber}/status")
    String getAccountStatus(@PathVariable("accountNumber") String accountNumber);

    @PutMapping("/{accountNumber}/deduct")
    void deductBalance(@PathVariable("accountNumber") String accountNumber,
                       @RequestParam("amount") BigDecimal amount,
                       @RequestParam("transactionId") String transactionId);

    @PutMapping("/{accountNumber}/credit")
    void creditBalance(@PathVariable("accountNumber") String accountNumber,
                       @RequestParam("amount") BigDecimal amount,
                       @RequestParam("transactionId") String transactionId);

    @PutMapping("/{accountNumber}/refund")
    void refundBalance(@PathVariable("accountNumber") String accountNumber,
                       @RequestParam("amount") BigDecimal amount,
                       @RequestParam("transactionId") String transactionId);

}
