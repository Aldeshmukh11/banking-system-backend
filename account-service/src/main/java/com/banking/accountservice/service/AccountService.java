package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.*;
import com.banking.accountservice.exception.*;
import com.banking.accountservice.repository.AccountRepository;
import com.banking.accountservice.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    private final LedgerEntryRepository ledgerEntryRepository;

    private static final SecureRandom SECURERANDOM = new SecureRandom();

    public AccountResponse createAccount(CreateAccountRequest request) {
        log.info("Creating account for: {}", request.getEmail());

        if(accountRepository.existsByEmail(request.getEmail())){
            throw new AccountAlreadyExistsException(request.getEmail());
        }

        Account account = new Account();
        account.setAccountHolderName(request.getAccountHolderName());
        account.setEmail(request.getEmail());
        account.setPhone(request.getPhone());
        account.setAccountType(request.getAccountType());
        account.setAccountStatus(AccountStatus.ACTIVE);
        account.setBalance(request.getInitialDeposit());
        account.setAccountNumber(generateAccountNumber());
        account.setDailyTransactionLimit(
                request.getAccountType() == AccountType.SAVINGS
                ? new BigDecimal("100000")
                : new BigDecimal("500000")
        );

        Account savedAccount = accountRepository.save(account);
        log.info("Account created: {}", savedAccount.getAccountNumber());

        return mapToResponse(savedAccount);

    }

    /**
     * Get account details by account number
     * @param accountNumber -> String
     * @return -> AccountResponse
     */
    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountNumber){
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
        return mapToResponse(account);
    }


    /**
     * Get account balance
     * @param accountNumber -> String
     * @return -> BigDecimal
     */

    public BigDecimal getBalance(String accountNumber){
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        return account.getBalance();
    }


    /**
     * Block account - called by Fraud Detection Service via Kafka
     * @param accountNumber -> String
     */
    @Transactional
    public void blockAccount(String accountNumber){
        log.info("Blocking account: {}", accountNumber);
        Account account = accountRepository.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        account.setAccountStatus(AccountStatus.BLOCKED);
        log.info("Account blocked: {}", accountNumber);
    }

    /**
     * Deduct balance from sender account
     * Called by Transaction Service via Kafka
     * @param accountNumber -> String
     * @param amount -> BigDecimal
     */

    @Transactional
    public void deductBalance(String transactionId, String accountNumber, BigDecimal amount){
        log.info("Deducting balance {} from account: {}", amount, accountNumber);

        // To check if the amount is positive or not
        requirePositive(amount);

        // Idempotency: already processed? just return
        if (ledgerEntryRepository.existsByTransactionIdAndEntryType(transactionId, EntryType.DEBIT)) {
            log.warn("Debit already processed for txn {}", transactionId);
            return;
        }

        Account account = accountRepository.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        if(account.getAccountStatus() != AccountStatus.ACTIVE){
            throw new AccountInactiveException(accountNumber);
        }

        if(account.getBalance().compareTo(amount) < 0){
            throw new InsufficientFundsException(accountNumber);
        }

        account.setBalance(account.getBalance().subtract(amount));

        log.info("Balance updated. New Balance: {}", account.getBalance());

        ledgerEntryRepository.save(new LedgerEntry(transactionId, accountNumber, amount, EntryType.DEBIT));
    }

    /**
     * Credit balance to receiver account
     * Called by Transaction Service via Kafka
     * @param accountNumber -> String
     * @param amount -> BigDecimal
     */

    @Transactional
    public void creditBalance(String transactionId, String accountNumber, BigDecimal amount){
        log.info("Crediting {} to account: {}", amount, accountNumber);

        // To check if the amount is positive or not
        requirePositive(amount);


        // Serializes credit vs refund for this transaction
        LedgerEntry debit = ledgerEntryRepository.findForUpdate(transactionId, EntryType.DEBIT)
                .orElseThrow(() -> new InvalidTransactionStateException("No debit found for txn " + transactionId));

        if (debit.getAmount().compareTo(amount) != 0) {
            throw new InvalidAmountException("Amount does not match the debited amount");
        }


        // 1. Idempotent: already credited?
        if (ledgerEntryRepository.existsByTransactionIdAndEntryType(transactionId, EntryType.CREDIT)) {
            log.warn("Credit already processed for txn {}", transactionId);
            return;
        }


        // 2. Safety: don't credit a transaction that was already refunded
        if (ledgerEntryRepository.existsByTransactionIdAndEntryType(transactionId, EntryType.REFUND)) {
            log.error("Txn: {} already refunded", transactionId);
            throw new InvalidTransactionStateException("Txn " + transactionId + " already refunded");
        }

        Account account = accountRepository.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        if(account.getAccountStatus() != AccountStatus.ACTIVE){
            throw new AccountInactiveException(accountNumber);
        }

        account.setBalance(account.getBalance().add(amount));

        ledgerEntryRepository.save(new LedgerEntry(transactionId, accountNumber, amount, EntryType.CREDIT));
        log.info("Credited {} to account {} for txn {}", amount, accountNumber, transactionId);
    }


    @Transactional
    public void refundBalance(String transactionId, String accountNumber, BigDecimal amount) {


        // To check if the amount is positive or not
        requirePositive(amount);

        log.info("Refunding {} to account: {}", amount, accountNumber);


        LedgerEntry debit = ledgerEntryRepository.findForUpdate(transactionId, EntryType.DEBIT)
                .orElseThrow(() -> new InvalidTransactionStateException("No debit found for txn " + transactionId));

        // Refund goes back to the account that was actually debited, for the debited amount
        if (!debit.getAccountNumber().equals(accountNumber) || debit.getAmount().compareTo(amount) != 0) {
            throw new InvalidAmountException("Refund does not match the original debit");
        }
        if (ledgerEntryRepository.existsByTransactionIdAndEntryType(transactionId, EntryType.REFUND)) {
            log.info("Refund already initiated for: {}", accountNumber);
            return;
        }

        if (ledgerEntryRepository.existsByTransactionIdAndEntryType(transactionId, EntryType.CREDIT)) {
            log.info("For the Txn: {} Amount {} already credited can not refund ",transactionId, amount);
            throw new InvalidTransactionStateException("Txn " + transactionId + " already credited, cannot refund");
        }

        Account account = accountRepository.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        account.setBalance(account.getBalance().add(amount));

        ledgerEntryRepository.save(new LedgerEntry(transactionId, accountNumber, amount, EntryType.REFUND));
    }

    // generate a unique 12-digit account number
    private String generateAccountNumber(){
        String accountNumber;

        do{

            // to generate a 12-digit account number
            // from 0 to 999999999999(1000000000000 - 1)
            long number = SECURERANDOM.nextLong(1_000_000_000_000L);

            accountNumber = String.format("%012d",number);

        }while (accountRepository.existsByAccountNumber(accountNumber));

        return accountNumber;
    }


    private AccountResponse mapToResponse(Account account){
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getAccountHolderName(),
                account.getEmail(),
                account.getPhone(),
                account.getAccountType(),
                account.getAccountStatus(),
                account.getBalance(),
                account.getDailyTransactionLimit(),
                account.getCreatedAt()
        );
    }


    public Map<String, Object> getCustomerEmailAndBalance(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));

        return Map.of(
                "email", account.getEmail(),
                "balance",account.getBalance()
        );
    }


    /**
     * To get Account status for Transaction Service
     * to check if the account is blocked or not before processing the transaction
     */
    public String getAccountStatus(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
        AccountStatus accountStatus = account.getAccountStatus();
        return accountStatus.toString();
    }


    private void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidAmountException("Amount must be greater than zero");
        }
    }
}
