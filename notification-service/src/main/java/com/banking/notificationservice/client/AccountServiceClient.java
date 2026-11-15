package com.banking.notificationservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

@FeignClient(name = "account-service",
        url = "${account.service.url}",
        path = "/api/v1/accounts")
public interface AccountServiceClient {

    @GetMapping("/{accountNumber}/balance-email")
    Map<String, Object> getCustomerEmailAndBalance(
            @PathVariable("accountNumber") String accountNumber);
}
