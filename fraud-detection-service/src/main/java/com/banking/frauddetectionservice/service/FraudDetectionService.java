package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.client.AccountServiceClient;
import com.banking.frauddetectionservice.model.FraudCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudDetectionService {

    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${fraud.max-transactions-per-minute}")
    private int maxTransactionsPerMinute;

    @Value("${fraud.suspicious-amount-multiplier}")
    private double suspiciousAmountMultiplier;

    @Value("${fraud.max-balance-percentage}")
    private double maxBalancePercentage;

    private static final String VERIFICATION_REQUIRED_TOPIC = "verification.required";
    private static final String FRAUD_CHECK_CLEAN_RESULT_TOPIC = "fraud.check.clean";
    private static final String TRANSACTION_REJECTED_TOPIC = "transaction.rejected";
    private static final int OTP_EXPIRY_MINUTES = 5;

    public void checkTransaction(Map<String, Object> payload) {
        String transactionId = payload.get("transactionId").toString();
        String accountNumber = payload.get("senderAccountNumber").toString();
        BigDecimal amount = new BigDecimal(payload.get("amount").toString());


        // Fetch real balance and email from Account Service
        Map<String, Object> customerEmailAndBalance = accountServiceClient.getCustomerEmailAndBalance(accountNumber);
        BigDecimal senderBalance = new BigDecimal(customerEmailAndBalance.get("balance").toString());
        String customerEmail = customerEmailAndBalance.get("email").toString();

        log.info("Checking transaction: {} account: {} amount: {} balance: {}",
                transactionId, accountNumber, amount, senderBalance);

        FraudCheckResult result = performFraudChecks(accountNumber, amount, senderBalance);

        if(result.isFraud()){

            log.info("Suspicious activity detected - account: {}" +
                    "reason: {} - requesting OTP verification",
                    accountNumber,result.getReason());

            String pendingKey = "fraud:pending:" + accountNumber;


            // Atomic: only one transaction per account can be awaiting OTP
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                    pendingKey,
                    transactionId,
                    Duration.ofMinutes(OTP_EXPIRY_MINUTES)
            );


            Map<String, Object> verificationEvent = new HashMap<>();
            verificationEvent.put("transactionId",transactionId);
            verificationEvent.put("accountNumber",accountNumber);
            verificationEvent.put("email",customerEmail);
            verificationEvent.put("amount",amount);
            verificationEvent.put("reason",result.getReason());

            kafkaTemplate.send(VERIFICATION_REQUIRED_TOPIC,transactionId,verificationEvent);
        }
        else {
            // Transaction clean
            log.info("Transaction clean");

            Map<String, Object> transactionCleanEvent = new HashMap<>();
            transactionCleanEvent.put("transactionId",transactionId);
            transactionCleanEvent.put("isFraud", false);
            transactionCleanEvent.put("reason",null);

            kafkaTemplate.send(FRAUD_CHECK_CLEAN_RESULT_TOPIC, transactionId, transactionCleanEvent);

        }
    }


    /**
     * Runs the three fraud patterns in order.
     * The first one that fails decides the result.
     *
     * A flagged result sends the transaction to OTP verification.
     * Clean results go straight to completion.
     */
    private FraudCheckResult performFraudChecks(
            String accountNumber,
            BigDecimal amount,
            BigDecimal senderBalance) {

        // Pattern 1: Velocity Check - more than 5 transactions in 60 seconds
        if(isVelocityExceeded(accountNumber)){
            return new FraudCheckResult(
                    true,"Too many transactions in 60 seconds " +
                    " - Velocity limit exceeded");
        }

        // Pattern 2: Amount check - more than 3x the sender's average
        if(isAmountSuspicious(accountNumber,amount)){
            return new FraudCheckResult(
                    true,"Unusual transaction amount " +
                    " - exceeds 3x your average");
        }

        // Pattern 3: Balance check - more than 90% of the current balance
        if(senderBalance.compareTo(BigDecimal.ZERO) > 0
               && isBalanceCheckFailed(senderBalance,amount)){
            return new FraudCheckResult(
                    true,"Transaction exceeds 90% of account balance");
        }

        return new FraudCheckResult(false,null);
    }

    /**
     * Pattern 1 - Velocity check.
     * Counts transactions per sender in a rolling 60-second window (window starts at the first transaction).
     * Transactions 1 to maxTransactionsPerMinute (5) pass without OTP.
     * Transaction 6 and every later one in the same window is flagged and needs OTP.
     */
    private boolean isVelocityExceeded(String accountNumber) {
        String key = "fraud:velocity:" + accountNumber;
        Long count = redisTemplate.opsForValue().increment(key);

        // Start the 60 seconds window on the first transaction
        if(count != null && count == 1){
            redisTemplate.expire(key, Duration.ofSeconds(60));
        }

        log.info("Velocity check - account: {} count: {}/{}",
                accountNumber, count, maxTransactionsPerMinute);

        return count != null && count > maxTransactionsPerMinute;
    }


    private boolean isAmountSuspicious(String accountNumber, BigDecimal amount) {
        String avgKey = "fraud:avg_amount:" + accountNumber;
        String countKey = "fraud:transaction_count:" + accountNumber;

        // Get previous average and transaction count
        String avgStr = redisTemplate.opsForValue().get(avgKey);
        String countStr = redisTemplate.opsForValue().get(countKey);

        // First transaction
        if (avgStr == null) {

            redisTemplate.opsForValue().set(avgKey, amount.toString());
            redisTemplate.opsForValue().set(countKey, "1");

            return false;
        }

        BigDecimal avgAmount = new BigDecimal(avgStr);
        long count = (countStr != null) ? Long.parseLong(countStr) : 1L;

        // Calculate suspicious threshold using previous average
        BigDecimal threshold = avgAmount.multiply(
                BigDecimal.valueOf(suspiciousAmountMultiplier));

        boolean suspicious = amount.compareTo(threshold) > 0;

        // Update running average only for non-suspicious transactions
        if (!suspicious) {

            BigDecimal totalAmount = avgAmount.multiply(BigDecimal.valueOf(count))
                    .add(amount);

            long newCount = count + 1;

            BigDecimal newAvg = totalAmount.divide(
                    BigDecimal.valueOf(newCount),
                    2,
                    RoundingMode.HALF_UP
            );

            // Save updated average and count in Redis
            redisTemplate.opsForValue().set(avgKey, newAvg.toString());
            redisTemplate.opsForValue().set(countKey, String.valueOf(newCount));
        }

        log.info("Amount check - amount: {} threshold: {} suspicious: {}",
                amount, threshold, suspicious);

        return suspicious;
    }


    private boolean isBalanceCheckFailed(BigDecimal senderBalance, BigDecimal amount) {
        BigDecimal maxAllowed = senderBalance.multiply(
                BigDecimal.valueOf(maxBalancePercentage));

        log.info("Balance check - amount: {} maxAllowed: {} suspicious: {}",
                amount, maxAllowed, amount.compareTo(maxAllowed) > 0);

        return amount.compareTo(maxAllowed) > 0;
    }


}
