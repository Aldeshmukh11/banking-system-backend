package com.banking.transactionservice.service;

import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.exception.TransactionNotFoundException;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionEventConsumer {

    private final TransactionRepository transactionRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TransactionService transactionService;

    private static final SecureRandom SECURERANDOM = new SecureRandom();

    private static final long OTP_EXPIRY_MINUTES = 5;
    private static final String TRANSACTION_OTP_GENERATED_TOPIC = "transaction.otp.generated";

    /**
     * Consumes verification.required (the fraud check flagged this transaction).
     *
     * RULE: Only one OTP may be pending per sender. If another transaction for the
     *       same sender is already PENDING_VERIFICATION, this one is cancelled and
     *       no OTP is generated.
     *
     * Otherwise: generate a 6-digit OTP (valid for 5 minutes), mark the transaction
     * PENDING_VERIFICATION, and send the OTP to the customer.
     *
     * @param payload -> Map<String, Object>
     */
    @KafkaListener(topics = "verification.required")
    public void consumeVerificationRequired(
            @Payload Map<String, Object> payload){

            String transactionId = payload.get("transactionId").toString();
            String accountNumber = payload.get("accountNumber").toString();
            String reason = payload.get("reason").toString();
            String email = payload.get("email").toString();

            log.info("Verification required - transaction: {} reason: {}",
                    transactionId, reason);

            Transaction transaction = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new TransactionNotFoundException(transactionId));

            if(transaction.getStatus() != TransactionStatus.PROCESSING){
                log.warn("Transaction {} not PROCESSING - skipping",transactionId);
                return;
            }


            // RULE: one pending OTP per sender
            if(transactionRepository.existsBySenderAccountNumberAndStatus(
                    accountNumber, TransactionStatus.PENDING_VERIFICATION)){
                log.warn("Account {} already has a transaction awaiting OTP - cancelling {}",
                        accountNumber, transactionId);
                transactionService.cancelTransaction(transaction,
                        "Another transaction is already awaiting OTP verification");
                return;
            }

            // Generate 6-digit otp
            String otp = String.format("%06d", SECURERANDOM.nextInt(1000000));

            // Store OTP in Redis - expires in 5 minutes
            String otpKey = "verification:otp:" + transactionId;
            redisTemplate.opsForValue().set(otpKey, otp, Duration.ofMinutes(OTP_EXPIRY_MINUTES));


            log.info("OTP generated for transaction: {} expires in {} min",
                    transactionId, OTP_EXPIRY_MINUTES);

            // Notify User
            Map<String, Object> otpEvent = new HashMap<>();
            otpEvent.put("transactionId",transactionId);
            otpEvent.put("accountNumber",accountNumber);
            otpEvent.put("email",email);
            otpEvent.put("reason",reason);
            otpEvent.put("otp",otp);
            otpEvent.put("amount",payload.get("amount"));

            // Send BEFORE changing status; join() surfaces a send failure so the listener retries
            kafkaTemplate.send(TRANSACTION_OTP_GENERATED_TOPIC, transactionId, otpEvent).join();


            // Update Status
            transaction.setStatus(TransactionStatus.PENDING_VERIFICATION);
            transactionRepository.save(transaction);

    }


    /**
     * Consumes fraud.check.clean (transaction passed the fraud check).
     * Money is deducted and the transaction completed in TransactionService.
     */
    @KafkaListener(topics = "fraud.check.clean")
    public void consumeFraudCheckCleanResult(
            @Payload Map<String, Object> payload){

            transactionService.processCleanResult(payload.get("transactionId").toString());

    }

    @KafkaListener(
            topicPattern = ".*\\.DLT",
            groupId = "transaction-service-dlt",
            properties = "value.deserializer=org.apache.kafka.common.serialization.StringDeserializer")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        Header h = record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        String error = h == null ? "n/a" : new String(h.value(), StandardCharsets.UTF_8);
        log.error("DEAD LETTER from {}: key={} error={} value={}",
                record.topic(), record.key(), error, record.value());
    }

}
