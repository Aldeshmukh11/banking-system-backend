package com.banking.transactionservice.service;

import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import com.banking.transactionservice.event.TransactionCompletedEvent;
import com.banking.transactionservice.event.TransactionInitiatedEvent;
import com.banking.transactionservice.exception.*;
import com.banking.transactionservice.repository.TransactionRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String TRANSACTION_INITIATED_TOPIC = "transaction.initiated";
    private static final String TRANSACTION_COMPLETED_TOPIC = "transaction.completed";
    private static final String TRANSACTION_CANCELLED_TOPIC = "transaction.cancelled";
    private static final String FRAUD_DETECTED_TOPIC = "fraud.detected";
    private static final String TRANSACTION_REFUNDED_TOPIC  = "transaction.refunded";

    /**
     * SAGA STEP 1 - Initiate transfer
     *
     * RULE 1: Only one transaction may wait for OTP per sender.
     *         If one is PENDING_VERIFICATION, reject the new transfer immediately.
     *         Nothing is saved or published, so no fraud check runs and no OTP is generated.
     *
     * RULE 2: Money is NOT deducted here. The transaction is saved as PROCESSING and
     *         published for fraud check. Money moves only in completeTransaction(),
     *         after the fraud check passes or the OTP is verified.
     *
     * @param request -> TransferRequest
     * @return -> TransactionResponse (status PROCESSING)
     */
    public TransactionResponse transfer(TransferRequest request){
        String sender = request.getSenderAccountNumber();
        String receiver = request.getReceiverAccountNumber();

        log.info("SAGA START - Transfer: {} -> {} amount: {}",
                sender,
                receiver,
                request.getAmount());

        // To check if the amount is positive or not
        requirePositive(request.getAmount());

        if (sender.equals(receiver)) {
            throw new InvalidTransferException("Sender and receiver accounts must be different");
        }


        if(transactionRepository.existsBySenderAccountNumberAndStatus(
                sender, TransactionStatus.PENDING_VERIFICATION)){
            log.warn("Transfer rejected - sender {} has a transaction awaiting OTP", sender);
            throw new PendingVerificationException();
        }

        if("BLOCKED".equals(fetchAccountStatus(sender))){
            log.warn("Transfer rejected - sender account: {} is BLOCKED", sender);
            throw new AccountBlockedException(sender);
        }


        // SAGA STEP - 1: Deduct from sender
//        accountServiceClient.deductBalance(
//                request.getSenderAccountNumber(),
//                request.getAmount());

        Transaction transaction = new Transaction();
        transaction.setSenderAccountNumber(request.getSenderAccountNumber());
        transaction.setReceiverAccountNumber(request.getReceiverAccountNumber());
        transaction.setAmount(request.getAmount());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.PROCESSING);
        transaction.setDescription(request.getDescription());
        transaction.setReferenceNumber(UUID.randomUUID().toString());

        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Transaction saved as PROCESSING: {}",savedTransaction.getId());

        // SAGA STEP - 2: Publish for fraud check
        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                savedTransaction.getId(),
                savedTransaction.getSenderAccountNumber(),
                savedTransaction.getReceiverAccountNumber(),
                savedTransaction.getAmount(),
                savedTransaction.getDescription()
        );

        kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC, savedTransaction.getId(),event);
        log.info("SAGA STEP 2 - TransactionInitiatedEvent published: {}",savedTransaction.getId());

        return mapToResponse(savedTransaction);
    }


    public TransactionResponse getTransaction(String transactionId) {
        return mapToResponse(transactionRepository
                .findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId)));
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber){
        return transactionRepository
                .findBySenderAccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }



    /**
     * Verify the OTP for a transaction that the fraud check flagged.
     *
     * RULE: Only PENDING_VERIFICATION transactions can be verified.
     *  - OTP expired  -> CANCELLED (nothing was deducted, so nothing to refund)
     *  - Wrong OTP    -> account blocked, transaction CANCELLED
     *  - Correct OTP  -> money deducted and transaction COMPLETED
     */

    public TransactionResponse verifyOTP(String transactionId, String otp) {
        log.info("OTP verification for the transaction: {}", transactionId);

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (transaction.getStatus() != TransactionStatus.PENDING_VERIFICATION) {
            throw new InvalidTransactionStateException(
                    "Transaction " + transactionId + " is not awaiting OTP verification");
        }

        String otpKey = "verification:otp:" + transactionId;
        String storedOtp = redisTemplate.opsForValue().get(otpKey);

        if (storedOtp == null) {
            // OTP EXPIRED
            log.warn("OTP expired for transaction: {}", transactionId);
//            compensateTransaction(transaction, "OTP expired - transaction cancelled amount refunded");
            cancelTransaction(transaction, "OTP expired - transaction cancelled");
            return mapToResponse(transaction);
        }

        if (!storedOtp.equals(otp)) {
            // BLOCK ACCOUNT AND REFUND
            log.warn("Wrong OTP - blocking account and refunding: {}", transactionId);
            redisTemplate.delete(otpKey);
//            blockAccountAndCompensate(transaction,
//                    "Wrong OTP entered - transaction cancelled, " +
//                            "account blocked for security");
            blockAccountAndCancel(transaction,
                    "Wrong OTP entered - transaction cancelled, account blocked for security");
            return mapToResponse(transaction);
        }

        // OTP correct - complete transaction
        log.info("OTP verified - completing transaction: {}", transactionId);
        redisTemplate.delete(otpKey);
        completeTransaction(transaction);
        return mapToResponse(transaction);

    }


//    private void compensateTransaction(Transaction transaction, String reason) {
//        log.warn("SAGA COMPENSATION - refunding: {} amount: {}",
//                transaction.getSenderAccountNumber(),
//                transaction.getAmount());
//
//        // CREDIT MONEY BACK TO SENDER SYNCHRONOUSLY
//        accountServiceClient.creditBalance(
//                transaction.getSenderAccountNumber(),
//                transaction.getAmount());
//
//        transaction.setStatus(TransactionStatus.FLAGGED);
//        transaction.setFailureReason(reason +
//                " - SAGA Compensation executed, amount refunded at " + LocalDateTime.now());
//
//        transactionRepository.save(transaction);
//
//        // PUBLISH refund event - Notification service will alert user
//        Map<String, Object> refundEvent = new HashMap<>();
//        refundEvent.put("transactionId", transaction.getId());
//        refundEvent.put("senderAccountNumber", transaction.getSenderAccountNumber());
//        refundEvent.put("amount", transaction.getAmount());
//        refundEvent.put("reason", reason);
//
//        kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC, transaction.getId(), refundEvent);
//
//        log.info("SAGA COMPENSATION COMPLETE - {} refunded to {}",
//                transaction.getAmount(), transaction.getSenderAccountNumber());
//
//    }

//    private void blockAccountAndCompensate(Transaction transaction, String reason) {
//
//        // Publish fraud.detected -> Account service will block account
//        Map<String, Object> fraudEvent = new HashMap<>();
//        fraudEvent.put("transactionId", transaction.getId());
//        fraudEvent.put("accountNumber", transaction.getSenderAccountNumber());
//        fraudEvent.put("reason", reason);
//
//        kafkaTemplate.send(FRAUD_DETECTED_TOPIC, transaction.getSenderAccountNumber(), fraudEvent);
//        log.warn("fraud.detected published - account: {} will be blocked. Kindly contact to the bank",
//                transaction.getSenderAccountNumber());
//
//        // SAGA COMPENSATION - refund Sender
//        compensateTransaction(transaction, reason);
//    }


    private void blockAccountAndCancel(Transaction transaction, String reason){

        // Publish fraud.detected -> Account service will block account
        Map<String, Object> fraudEvent = new HashMap<>();
        fraudEvent.put("transactionId", transaction.getId());
        fraudEvent.put("accountNumber", transaction.getSenderAccountNumber());
        fraudEvent.put("reason", reason);

        kafkaTemplate.send(FRAUD_DETECTED_TOPIC, transaction.getSenderAccountNumber(), fraudEvent);
        log.warn("fraud.detected published - account: {} will be blocked. Kindly contact to the bank",
                transaction.getSenderAccountNumber());

        cancelTransaction(transaction, reason);

    }



    /**
     * SAGA STEP 3 - Debit sender, then credit receiver.
     * Safe to call again for DEBIT_PENDING / DEBITED transactions: every remote
     * call is idempotent (keyed by transactionId) and finished steps are skipped.
     * Intentionally NOT @Transactional: it makes remote calls, and each save()
     * should commit on its own so progress survives a crash.
     */
    private void completeTransaction(Transaction transaction) {

        String txnId = transaction.getId();
        String sender = transaction.getSenderAccountNumber();
        String receiver = transaction.getReceiverAccountNumber();
        BigDecimal amount = transaction.getAmount();

        // STEP 1: debit sender (skipped if already DEBITED)
        if (transaction.getStatus() != TransactionStatus.DEBITED) {
            // Record intent BEFORE the remote call so a crash is recoverable
            transaction.setStatus(TransactionStatus.DEBIT_PENDING);
            transactionRepository.save(transaction);

            try {
                accountServiceClient.deductBalance(sender, amount, txnId);
            } catch (FeignException.FeignClientException e) {
                // 4xx: definite rejection (insufficient funds, inactive...) -> nothing debited
                log.error("Debit rejected for txn {}: {}", txnId, e.getMessage());
                transaction.setStatus(TransactionStatus.FAILED);
                transaction.setFailureReason("Debit rejected - " + e.getMessage());
                transactionRepository.save(transaction);
                return;
            } catch (Exception e) {
                // timeout / 5xx: outcome UNKNOWN. Stay DEBIT_PENDING; reconciler retries.
                log.error("Debit outcome unknown for txn {}, will retry: {}", txnId, e.getMessage());
                return;
            }

            transaction.setStatus(TransactionStatus.DEBITED);
            transactionRepository.save(transaction);
        }

        // STEP 2: credit receiver
        try {
            accountServiceClient.creditBalance(receiver, amount, txnId);
        } catch (FeignException.FeignClientException e) {
            // 4xx: receiver definitely NOT credited -> compensate the sender
            log.error("Credit rejected for txn {}: {}", txnId, e.getMessage());
            refundSender(transaction, "Credit failed - " + e.getMessage());
            return;
        } catch (Exception e) {
            // Unknown: receiver MAY have been credited. NEVER refund here. Stay DEBITED; retry later.
            log.error("Credit outcome unknown for txn {}, will retry: {}", txnId, e.getMessage());
            return;
        }

        // STEP 3: done
        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        transactionRepository.save(transaction);

        kafkaTemplate.send(TRANSACTION_COMPLETED_TOPIC, txnId, new TransactionCompletedEvent(
                txnId, sender, receiver, amount, transaction.getDescription()));

        log.info("SAGA COMPLETED - Transaction {}", txnId);
    }


    /** SAGA COMPENSATION - return the debited amount to the sender. */
    private void refundSender(Transaction transaction, String reason) {
        log.info("SAGA COMPENSATION - refunding sender {} amount {} for txn {}",
                transaction.getSenderAccountNumber(),
                transaction.getAmount(),
                transaction.getId());

        transaction.setStatus(TransactionStatus.REVERSAL_PENDING);
        transaction.setFailureReason(reason + " at " + LocalDateTime.now());
        transactionRepository.save(transaction);

        try {
            accountServiceClient.refundBalance(
                    transaction.getSenderAccountNumber(), transaction.getAmount(), transaction.getId());
        } catch (FeignException e) {
            if (e.status() == 409) {
                // Account Service says "already credited": the credit actually succeeded earlier
                log.warn("Refund refused for txn {} - receiver was already credited, marking COMPLETED",
                        transaction.getId());
                transaction.setStatus(TransactionStatus.COMPLETED);
                transaction.setCompletedAt(LocalDateTime.now());
                transactionRepository.save(transaction);
                return;
            }
            log.error("Refund failed for txn {}, will retry: {}", transaction.getId(), e.getMessage());
            return; // stays REVERSAL_PENDING
        } catch (Exception e) {
            log.error("Refund failed due to exception for txn {}, will retry: {}", transaction.getId(), e.getMessage());
            return;
        }

        transaction.setStatus(TransactionStatus.REVERSED);
        transactionRepository.save(transaction);

        kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC, transaction.getId(), Map.of(
                "transactionId", transaction.getId(),
                "senderAccountNumber", transaction.getSenderAccountNumber(),
                "amount", transaction.getAmount(),
                "reason", reason));

        log.info("SAGA COMPENSATION COMPLETE - txn {} refunded", transaction.getId());
    }



    /**
     * Called when the fraud check passes (fraud.check.clean) or from verifyOTP.
     * Only PROCESSING transactions are completed.
     */
    public void processCleanResult(String transactionId) {

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if(transaction.getStatus() != TransactionStatus.PROCESSING){
            log.warn("Transaction {} not PROCESSING - skipping",transactionId);
            return;
        }

        completeTransaction(transaction);
    }


    /**
     * Safety net: retries transactions stuck mid-saga.
     */
    @Scheduled(fixedDelay = 60_000)
    public void reconcileStuckTransactions() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(2);

        // Debit/credit retry (idempotent on the Account Service side)
        for (TransactionStatus status : List.of(TransactionStatus.DEBIT_PENDING, TransactionStatus.DEBITED)) {
            transactionRepository.findByStatusAndUpdatedAtBefore(status, cutoff)
                    .forEach(t -> safely(t, this::completeTransaction));
        }

        // Refund retry
        transactionRepository.findByStatusAndUpdatedAtBefore(TransactionStatus.REVERSAL_PENDING, cutoff)
                .forEach(t -> safely(t, x -> refundSender(x, "Retrying refund")));


        // Expire OTPs nobody verified (OTP lives 5 min in Redis, so 6 min leaves a margin)
        transactionRepository.findByStatusAndUpdatedAtBefore(
                        TransactionStatus.PENDING_VERIFICATION, LocalDateTime.now().minusMinutes(6))
                .forEach(t -> safely(t, x -> {
                    Transaction fresh = transactionRepository.findById(x.getId()).orElse(null);
                    if (fresh == null || fresh.getStatus() != TransactionStatus.PENDING_VERIFICATION) return;
                    redisTemplate.delete("verification:otp:" + fresh.getId());
                    cancelTransaction(fresh, "OTP expired - transaction cancelled");
                }));
    }



    /**
     * Cancels a transaction. Money was never deducted, so there is nothing to refund.
     */
    public void cancelTransaction(Transaction transaction, String reason){
        transaction.setStatus(TransactionStatus.CANCELLED);
        transaction.setFailureReason(reason + " at " + LocalDateTime.now());
        transactionRepository.save(transaction);

        Map<String, Object> cancelledEvent = new HashMap<>();
        cancelledEvent.put("transactionId", transaction.getId());
        cancelledEvent.put("senderAccountNumber", transaction.getSenderAccountNumber());
        cancelledEvent.put("reason", reason);
        cancelledEvent.put("amount", transaction.getAmount());

        kafkaTemplate.send(TRANSACTION_CANCELLED_TOPIC, transaction.getId(), cancelledEvent);

        log.info("Transaction {} cancelled - reason: {}", transaction.getId(), reason);
    }



    private TransactionResponse mapToResponse(Transaction savedTransaction) {
        return new TransactionResponse(
                savedTransaction.getId(),
                savedTransaction.getSenderAccountNumber(),
                savedTransaction.getReceiverAccountNumber(),
                savedTransaction.getAmount(),
                savedTransaction.getType(),
                savedTransaction.getStatus(),
                savedTransaction.getDescription(),
                savedTransaction.getFailureReason(),
                savedTransaction.getReferenceNumber(),
                savedTransaction.getCreatedAt(),
                savedTransaction.getCompletedAt()
        );
    }


    private void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidAmountException();
        }
    }

    private String fetchAccountStatus(String accountNumber) {
        try {
            return accountServiceClient.getAccountStatus(accountNumber);
        } catch (FeignException.NotFound e) {
            throw new AccountNotFoundException(accountNumber);
        } catch (FeignException e) {
            // includes connection failures and timeouts
            log.error("Account service call failed: {}", e.getMessage());
            throw new ServiceUnavailableException("Account service is temporarily unavailable. Please try again.");
        }
    }


    private void safely(Transaction t, Consumer<Transaction> action) {
        try {
            action.accept(t);
        } catch (Exception e) {
            log.error("Reconcile failed for txn {}: {}", t.getId(), e.getMessage());
        }
    }

}
