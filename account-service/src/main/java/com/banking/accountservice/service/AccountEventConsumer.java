package com.banking.accountservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountEventConsumer {

    private static final String ACCOUNT_NUMBER_KEY = "accountNumber";

    private final AccountService accountService;

    /**
     * Consume fraud.detected event from Kafka
     * Blocks the flagged account.
     * @param payload -> Map<String, Object>
     */
    @KafkaListener(topics = "fraud.detected")
    public void consumeFraudDetected(@Payload Map<String, Object> payload) {

        String accountNumber = extractAccountNumber(payload);
        log.info("Fraud detected - blocking account: {}", accountNumber);

        accountService.blockAccount(accountNumber);
    }

    /**
     * Validates the event and returns the account number.
     * The value must be a non-blank String: account numbers are zero-padded,
     * so a numeric JSON value would silently lose its leading zeros.
     */
    private String extractAccountNumber(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("fraud.detected event has no payload");
        }

        Object value = payload.get(ACCOUNT_NUMBER_KEY);

        if (!(value instanceof String accountNumber) || accountNumber.isBlank()) {
            throw new IllegalArgumentException(
                    "fraud.detected event has missing or invalid '" + ACCOUNT_NUMBER_KEY + "'");
        }

        return accountNumber;
    }
}