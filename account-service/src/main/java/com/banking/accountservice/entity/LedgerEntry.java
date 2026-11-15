package com.banking.accountservice.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "account_ledger",
        uniqueConstraints = @UniqueConstraint(columnNames = {"transactionId", "entryType"}))
@Data
@NoArgsConstructor
public class LedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String transactionId;     // from Transaction Service
    private String accountNumber;
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private EntryType entryType;      // DEBIT, REFUND

    private LocalDateTime createdAt = LocalDateTime.now();


    public LedgerEntry(String transactionId, String accountNumber, BigDecimal amount, EntryType entryType) {
        this.transactionId = transactionId;
        this.accountNumber = accountNumber;
        this.amount = amount;
        this.entryType = entryType;
    }
}
