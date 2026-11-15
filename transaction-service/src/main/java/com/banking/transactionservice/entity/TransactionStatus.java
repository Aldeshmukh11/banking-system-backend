package com.banking.transactionservice.entity;

/**
 * Transaction Lifecycle Flow:
 *
 * PENDING -> PROCESSING -> COMPLETED (clean transaction)
 *                       -> PENDING_VERIFICATION (suspicious detected)
 *                                 -> COMPLETED (verified)
 *                                 -> FLAGGED (SAGA REFUND)
 *                       -> FAILED
 *                       -> FLAGGED
 */
public enum TransactionStatus {

    PROCESSING,
    PENDING_VERIFICATION,
    DEBIT_PENDING,      // fraud passed, debit requested, outcome may be unknown
    DEBITED,            // sender debited, credit not yet confirmed
    COMPLETED,
    FAILED,
    CANCELLED, // OTP expired or wrong OTP, or blocked by a pending OTP. Nothing was deducted.
    FLAGGED,
    REVERSAL_PENDING,   // credit rejected, refund not yet confirmed
    REVERSED            // sender refunded
}
