package com.banking.transactionservice.repository;

import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    List<Transaction> findBySenderAccountNumberOrderByCreatedAtDesc(String accountNumber);


    // RULE: used to check whether a sender already has a transaction waiting for OTP
    boolean existsBySenderAccountNumberAndStatus(String senderAccountNumber, TransactionStatus status);

    List<Transaction> findByStatusAndUpdatedAtBefore(TransactionStatus status, LocalDateTime before);
}
