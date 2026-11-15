package com.banking.accountservice.repository;

import com.banking.accountservice.entity.EntryType;
import com.banking.accountservice.entity.LedgerEntry;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    boolean existsByTransactionIdAndEntryType(String transactionId, EntryType entryType);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT l FROM LedgerEntry l WHERE l.transactionId = :txnId AND l.entryType = :type")
    Optional<LedgerEntry> findForUpdate(@Param("txnId") String txnId, @Param("type") EntryType type);
}
