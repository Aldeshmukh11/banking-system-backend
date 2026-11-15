package com.banking.accountservice.service;

import com.banking.accountservice.exception.AccountNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountEventConsumerTest {

    private static final String ACC_NO = "000123456789";

    @Mock
    private AccountService accountService;

    @InjectMocks
    private AccountEventConsumer consumer;

    @Test
    void consumeFraudDetected_blocksAccountFromPayload() {
        consumer.consumeFraudDetected(Map.of("accountNumber", ACC_NO));

        verify(accountService).blockAccount(ACC_NO);
        verifyNoMoreInteractions(accountService);
    }

    @Test
    void consumeFraudDetected_preservesLeadingZeros() {
        consumer.consumeFraudDetected(Map.of("accountNumber", "000000000001"));

        verify(accountService).blockAccount("000000000001");
    }

    @Test
    void consumeFraudDetected_ignoresExtraFields() {
        consumer.consumeFraudDetected(Map.of(
                "accountNumber", ACC_NO,
                "reason", "velocity check",
                "score", 0.97));

        verify(accountService).blockAccount(ACC_NO);
    }

    @Test
    void consumeFraudDetected_throwsWhenPayloadIsNull() {
        assertThrows(IllegalArgumentException.class, () -> consumer.consumeFraudDetected(null));

        verifyNoInteractions(accountService);
    }

    @Test
    void consumeFraudDetected_throwsWhenAccountNumberMissing() {
        assertThrows(IllegalArgumentException.class,
                () -> consumer.consumeFraudDetected(Map.of("reason", "x")));

        verifyNoInteractions(accountService);
    }

    @Test
    void consumeFraudDetected_throwsWhenAccountNumberIsNull() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("accountNumber", null);

        assertThrows(IllegalArgumentException.class, () -> consumer.consumeFraudDetected(payload));

        verifyNoInteractions(accountService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    void consumeFraudDetected_throwsWhenAccountNumberBlank(String blank) {
        assertThrows(IllegalArgumentException.class,
                () -> consumer.consumeFraudDetected(Map.of("accountNumber", blank)));

        verifyNoInteractions(accountService);
    }

    @Test
    void consumeFraudDetected_throwsWhenAccountNumberIsNotString() {
        // a numeric value would silently lose leading zeros, so it is rejected
        assertThrows(IllegalArgumentException.class,
                () -> consumer.consumeFraudDetected(Map.of("accountNumber", 123456789012L)));

        verifyNoInteractions(accountService);
    }

    @Test
    void consumeFraudDetected_propagatesServiceException() {
        doThrow(new AccountNotFoundException(ACC_NO)).when(accountService).blockAccount(ACC_NO);

        assertThrows(AccountNotFoundException.class,
                () -> consumer.consumeFraudDetected(Map.of("accountNumber", ACC_NO)));
    }
}